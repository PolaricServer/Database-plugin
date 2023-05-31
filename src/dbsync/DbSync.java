/* 
 * Copyright (C) 2023 by Øyvind Hanssen (ohanssen@acm.org)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 */
 
package no.polaric.aprsdb.dbsync;
import no.polaric.aprsdb.*;
import no.polaric.aprsd.*;
import no.polaric.aprsd.http.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import no.polaric.aprsd.filter.*;
import java.util.stream.Collectors;
import java.sql.*;
import javax.sql.*;
import java.net.*;
import java.net.http.*;
import static spark.Spark.*;


/*
 * Replication with relaxed/eventual consistency. 
 */
 
public class DbSync implements Sync
{
    private ServerAPI _api;   
    private PluginApi _dbp;
    private DbSyncApi _dsapi; 
    private Map<String, Handler> _handlers = new HashMap<String, Handler>();
    private Timer hb = new Timer();
    private DuplicateChecker _dup = new DuplicateChecker(300);
    private HmacAuth _auth;
    private String _ident;
    

    /* Parent URL */
    private Map<String,String> _parent = new HashMap<String, String>();
    /* Websocket connected nodes */
    private NodeWsApi<ItemUpdate> _wsNodes;
    /* Configured nodes */
    private Map<String, String> _nodes = new HashMap<String, String>();
    /* Nodes on wait */
    private Map<String, Retry> _pending = new HashMap<String, Retry>();       
    
    
    private static class Retry {
        public int cnt, time;
        public Retry(int t) 
            { cnt=time=t; }
    }

    
    private long TS(long ts) {
        return ts - 1670677450000L;
    }
    
    
    
    public DbSync(ServerAPI api) {
        _api = api;
        _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
        
        _dbp.log().info("DbSync", "Initializing replication (eventual consistency)");
     
        
        hb.schedule( new TimerTask() 
            { public void run() {       
                updatePeers();
            } 
        } , 45000, 15000); 
        
        
     
        /* Set up of ident for this node. Use mycall as default */
        String mycall = api.getProperty("default.mycall", "NOCALL").toUpperCase();
        _ident = api.getProperty("db.sync.ident", mycall);
        

        /* Setup of servers for websocket comm. */
        NodeWs ws = new NodeWs(_api, null);
        webSocket("/ws/dbsync", ws);
        
        /* Set up websocket api and handler for item updates */
        _wsNodes = new NodeWsApi<ItemUpdate>(_ident, ws, ItemUpdate.class);
        _wsNodes.setHandler( (nodeid, upd)-> {
                if (upd==null)
                    _dbp.log().warn("DbSync", "Received ItemUpdate: "+nodeid+", NULL");
                else
                    _dbp.log().info("DbSync", "Received ItemUpdate: "+nodeid+", "
                      +upd.cid+", "+upd.itemid+", "+upd.cmd+", "+ TS(upd.ts));
                doUpdate((ItemUpdate) upd);
            } 
        );
        _auth = new HmacAuth(api, "system.auth.key");   
        setupNodes();
    }
    
    
    public void startRestApi() {
        _dsapi = new DbSyncApi(_api, _auth, this);    
        _dsapi.start();
    }
    
    
    
    public String getIdent() {
        return _ident; 
    }
    
    
    public boolean isConnected(String nodeid) {
        return _wsNodes.isConnected(nodeid); 
    }
    
    
    
    public String getNodeId(String url) 
        throws URISyntaxException, IOException,InterruptedException  
    {
        if (url == null)
            return null; 
        RestClient parentapi = new RestClient(_api, url, _auth);
        HttpResponse res = parentapi.GET("/nodeinfo");
        if (res.statusCode() != 200) {
            _dbp.log().warn("DbSync", "Couldn't get nodeid from parent. Status code: "+res.statusCode());
            return null; 
        }
        return (String) res.body();
    }
    

    
    public boolean addNodeRemote(String url, String items) 
        throws URISyntaxException, IOException, InterruptedException
    {
        RestClient parentapi = new RestClient(_api, url, _auth);
        String arg = ServerBase.serializeJson( new DbSyncApi.NodeInfo(_ident, items, url));
        HttpResponse res = parentapi.POST("/nodes", arg);
        if (res.statusCode() != 200) {
            _dbp.log().warn("DbSync", "Parent subscription failed. Status code: "+res.statusCode());
            return false; 
        }
        return true;
    }
    
    
    
    public boolean addNode(String nodeid, String items, String url) {
        SyncDBSession db = null;
        try {    
            db = new SyncDBSession(_dbp.getDB(false));
            
            /* Add node to database */
            db.addSyncPeer(nodeid, items, url);
            db.commit();
            _addNode(nodeid, items, url);       
            return true;
        }       
        catch (Exception e) {
            db.abort();
            _dbp.log().error("DbSync", "Exception: "+e.getMessage());  
            e.printStackTrace(System.out);
            return false;
        }     
        finally {
            if (db != null) db.close();
        }
    }
    
    
    
    public void rmNode(String nodeid) {
        SyncDBSession db = null;
        try {           
            db = new SyncDBSession(_dbp.getDB(true)); // Autocommit on
            db.removeSyncPeer(nodeid);
            _nodes.remove(nodeid);
            _wsNodes.rmNode(nodeid);
            _parent.remove(nodeid);
        }
        catch (Exception e) {
            _dbp.log().error("DbSync", "Exception: "+e.getMessage());  
            e.printStackTrace(System.out);
            return;
        }     
        finally {
            if (db != null) db.close();
        }
    }
    
        
        
    public boolean rmNodeRemote(String id) 
        throws URISyntaxException, IOException, InterruptedException
    {
        var url = _parent.get(id);
        RestClient parentapi = new RestClient(_api, url, _auth);
        HttpResponse res = parentapi.DELETE("/nodes/"+_ident);
        if (res.statusCode() != 200) {
            _dbp.log().warn("DbSync", "Parent unsubscribe failed. Status code: "+res.statusCode());
            return false; 
        }
        return true;
    }
    
    
    
    private void _addNode(String nodeid, String items, String url) {
        if (url != null) {
            /* Add parent node (create a client to it) 
             * Currently we assume there is at most one parent. We should consider 
             * allowing multiple parents or backup-parents 
             */
            NodeWsClient srv = new NodeWsClient(_api, nodeid, wsUrl(url), true);
            _wsNodes.addServer(nodeid, srv);
            _parent.put(nodeid, url);
            _dbp.log().info("DbSync", "Parent (server) node registered: "+nodeid+", "+url);
        }
        _nodes.put(nodeid, items);
    }
    
    
    
    private void setupNodes() {
        /* 
         * Read the setup of peer nodes from database. 
         * If URL is given, register as server-nodes. 
         */ 
        SyncDBSession db = null;
        try {           
            db = new SyncDBSession(_dbp.getDB(true)); // Autocommit on
            DbList<SyncDBSession.SyncPeer> peers = db.getSyncPeers(false); 
            for (SyncDBSession.SyncPeer p : peers) 
                if (p!=null) 
                    _addNode(p.nodeid, p.items, p.url);
        }
        catch (Exception e) {
            _dbp.log().error("DbSync", "Exception: "+e.getMessage());  
            e.printStackTrace(System.out);
            return;
        }     
        finally {
            if (db != null) db.close();
        }
    }
    
    
    
    private String wsUrl(String url) {
        return url.replaceFirst("http", "ws")
            .replaceFirst("/dbsync", "/ws/dbsync");
    }
     
    /**
     * Decide if update transaction should be performed or not. This is the
     * place for conflict resolution. By default we use a LWW strategy (last writer
     * wins). 
     */
    public boolean shouldCommit(SyncDBSession db, ItemUpdate upd) 
        throws SQLException
    {
        /* 
         * If we detect that the same update-message has been here before, 
         * we ignore it and set the propagate flag to false.
         */
        if (_dup.contains(upd.origin+upd.ts)) {
            upd.propagate = false;
            return false;
        }
        else
            _dup.add(upd.origin+upd.ts);
        
        /* Get the last executed command on the item id */
        SyncDBSession.SyncOp meta = db.getSync(upd.cid, upd.itemid);
        
        /* Last writer wins (LWW): Ignore command if it was issued before last executed command. */
        if (meta!=null && upd.ts <= meta.ts.getTime()) {
            _dbp.log().info("DbSync", TS(upd.ts)+" <= "+TS(meta.ts.getTime()));
            return false; 
        }
        
        /* Update comes after a delete */
        if ((meta==null || meta.cmd.equals("DEL")) && upd.cmd.equals("UPD")) {
            _dbp.log().info("DbSync", "Convert UPD to ADD: "+upd.cid+", "+upd.itemid);
            upd.cmd="ADD";
        }
            
        /* Add comes after add or update */
        if (meta!=null && meta.cmd.equals("ADD") && upd.cmd.equals("ADD")
            || (meta!=null && meta.cmd.equals("UPD") && upd.cmd.equals("ADD"))) {
            _dbp.log().info("DbSync", "Convert ADD to UPD: "+upd.cid+", "+upd.itemid);
            upd.cmd="UPD";
        }
        return true;
    }
 
    
    
    /**
     * Perform an update. 
     * This is called when a update message is received from another node.
     * Return true if performed/committed or ignored due to LWW conflict resolution. False on error.
     */
    public boolean doUpdate(ItemUpdate upd) 
    {  
        Handler hdl = _handlers.get(upd.cid);
        if (hdl==null) {
            _dbp.log().warn("DbSync", "Handler not found for: "+upd.cid);
            return false;
        }
            
        SyncDBSession db = null;
        try {
            db = new SyncDBSession(_dbp.getDB());
            if (!shouldCommit(db, upd)) {
                _dbp.log().info("DbSync", "Ignoring update for: "+upd.cid+", "+upd.itemid+" ["+upd.origin+"]");
                db.abort();
                return true;
            }        
            db.setSync(upd.cid, upd.itemid, upd.cmd, new java.util.Date(upd.ts));
            db.commit(); 
            
            hdl.handle(upd);
            return true;
        }
        catch(Exception e) {
            if (db != null) db.abort();
            _dbp.log().error("DbSync", "Exception: "+e.getMessage());
            e.printStackTrace(System.out);
            return false;
        }      
        finally {
            if (db != null) db.close();
            /* 
             * We need to propagate the update to the other peers. 
             * But not if the propagate flag is set to false and not to node from where we got the message! 
             */
            propagate(upd, upd.sender);
        }
    }
    
 
    /**
     * Register (queue) a local update for synchronizing to other nodes. .
     * This is called from various update methods.  
     */
    long prev_ts = 0;
    public void localUpdate(String cid, String itemid, String userid, String cmd, String arg) {
        ItemUpdate upd = new ItemUpdate(cid, itemid, userid, cmd, arg);
        upd.origin = _ident;
        
        /* 
         * If more than one local update is done within the same millisecond tick, 
         * we need to make sure the timestamps reflect the actual order. Ensure that the last 
         * update has a higher timestamp than the previous. 
         */
        if (upd.ts <= prev_ts)
            upd.ts++;
        prev_ts = upd.ts;
        
        propagate(upd, null); 
    }
    
    
    
    /**
     * Propagate a update to other nodes. .
     */
    public void propagate(ItemUpdate upd, String except) 
    {
        if (!upd.propagate)
            return;
        if (_nodes.isEmpty()) {
           _dbp.log().info("DbSync", "Outgoing update: "+upd.cid+", "+upd.itemid+" - no nodes");
            return;
        }

        SyncDBSession db = null;
        try {
            db = new SyncDBSession(_dbp.getDB());
        
            /* 
             * If local (originating from this node) 
             * Update timstamp and op locally in the database for the item 
             */
            if (except==null)
                db.setSync(upd.cid, upd.itemid, upd.cmd, new java.util.Date()); 
            
            /* Queue message for updating peer nodes */
            for (String nodeid : _nodes.keySet())
                if (upd.cid.matches(_nodes.get(nodeid)) && !nodeid.equals(except)) {   
                    _dbp.log().info("DbSync", "Queueing outgoing update: "+upd.cid+", "+upd.itemid 
                        + ", "+ upd.cmd +", "+ TS(upd.ts) 
                        + (upd.origin.equals(_ident) ? "" : " ["+upd.origin + "]") + " to: " + nodeid);
                    db.addSyncUpdate(nodeid, upd);
                }
            db.commit();
        }
        catch(Exception e) {
            if (db != null) db.abort();
            _dbp.log().error("DbSync", "Exception: "+e.getMessage());   
            e.printStackTrace(System.out);
        }
        finally {
            if (db != null) db.close();
        }
    }
 
 
    

    /* 
     * This is called periodically. 15 second interval. It attempts to send the updates
     * to the peer nodes. If request succeeds, it is deleted from the queue. If not, it 
     * is rescheduled and we will retry after a while. 
     */

    protected void updatePeers()
    {
        /* Go through all server nodes and connected client nodes */
        for (String node: _wsNodes.getNodes()) {
 
            /* If specified retry-time isn't reached yet, reschedule */
            Retry retr = _pending.get(node);
            _pending.remove(node);
            if (retr != null && retr.cnt > 0) {
                retr.cnt--;
                _pending.put(node, retr);
                continue;
            }
            
            /* Check if node is known */
            if (_nodes.get(node)==null) {
                _dbp.log().warn("DbSync", "Attempt to post to unknown node: "+node+" - ignoring");
                continue;
            }
                    
            /* Try to send updates through websocket connection */
            SyncDBSession db = null;
            try {
                /* Search database for items to be sent to node */
                db = new SyncDBSession(_dbp.getDB(true)); // Autocommit on
                DbList<ItemUpdate> msgs = db.getSyncUpdates(node);
                for (ItemUpdate upd : msgs) {
                    upd.sender = _ident;

                    /* Send each item to node */
                    if (!_wsNodes.put(node, upd)) {
                        /* If post to node failed..*/
                        if (retr == null) retr = new Retry(6);
                        else { 
                            /* Double retry-time each retry, max 1 hour */
                            if (retr.time < 240)
                                retr.cnt = retr.time = (retr.time * 2);
                         }
                        
                        _dbp.log().info("DbSync", "Post failed to node: "+node+" - rescheduling ("+retr.time*15+" s)");
                        _pending.put(node, retr);
                        break;
                    }
                    retr = null;
                    _dbp.log().info("DbSync", "Post to "+node+" succeeded ("+upd.cid+")");
                    db.removeSyncUpdates(node, new java.util.Date(upd.ts+1000));
                }    
            }
                            
            catch (Exception e) {
                _dbp.log().error("DbSync", "Exception: "+e.getMessage());  
                e.printStackTrace(System.out);
                return;
            }     
            finally {
                if (db != null) db.close();
            }
                
        }
    }
    
    
    
    
    /**
     * Register a handler with a cid. 
     */
    public void addCid(String cid, Handler hdl) {
        _handlers.put(cid, hdl);
    }
    

}
