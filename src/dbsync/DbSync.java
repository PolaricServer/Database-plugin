/* 
 * Copyright (C) 2025 by LA7ECA, Øyvind Hanssen (ohanssen@acm.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 */
 
 
package no.polaric.aprsdb.dbsync;
import no.polaric.aprsdb.*;
import no.polaric.aprsd.*;
import no.arctic.core.*;
import no.arctic.core.httpd.*;
import no.arctic.core.auth.*;

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


/*
 * Replication with strong eventual consistency. 
 */
 
public class DbSync implements Sync
{
    private ServerConfig _api;   
    private PluginApi _dbp;
    private DbSyncApi _dsapi; 
    private Timer hb = new Timer();
    private DuplicateChecker _dup = new DuplicateChecker(300);
    private HmacAuthenticator _auth;
    private String _ident;
    
    /* Handlers for dataset-types */
    private Map<String, Handler> _handlers = new HashMap<String, Handler>();

    /* Parent URL */
    private Map<String,String> _parent = new HashMap<String, String>();
    /* Websocket connected nodes */
    private NodeWsApi<Sync.Message> _wsNodes;
    /* Configured nodes */
    private Map<String, String> _nodes = new HashMap<String, String>();
    /* Nodes on wait */
    private Map<String, Retry> _pending = new HashMap<String, Retry>();       
    
    
    /* Policy for conflict resolution */
    private Map<String, String> _policy = new HashMap<String, String>();
    
    
    private static class Retry {
        public int cnt, time;
        public Retry(int t) 
            { cnt=time=t; }
    }

    
    private long TS(long ts) {
        return ts - 1670677450000L;
    }
    
    
    
    public DbSync(ServerConfig api) {
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
        ws.start("ws/dbsync");
        
        /* Set up websocket api and handler for item updates  
         */
        _wsNodes = new NodeWsApi<Message>(_api, _ident, ws, Message.class);
        _wsNodes.setHandler( (nodeid, msg)-> {
                if (msg==null)
                    _dbp.log().warn("DbSync", "Received Message: "+nodeid+", NULL");
                    
                else if (msg.mtype == MsgType.UPDATE) {
                    _dbp.log().info("DbSync", "Received ItemUpdate: "+nodeid+", "
                      +msg.update.cid+", "+msg.update.itemid+", "+msg.update.cmd+", "+ TS(msg.update.ts));
                    msg.update.sender = msg.sender;
                    doUpdate(msg.update, msg.sender);
                }
                
                else if (msg.mtype == MsgType.ACK) {
                    _dbp.log().info("DbSync", "Received Ack: "+nodeid+", "+msg.ack.origin+", "+msg.ack.ts);
                    doAck(msg.ack, msg.sender);
                }
            } 
        );
        setupNodes();
    }
    
    
    
    public void startRestApi() {
        _dsapi = new DbSyncApi(_api, this);    
        _dsapi.start();
    }
    
    
    
    public Handler getHandler(String cid) {
        return _handlers.get(cid);
    }
    
    
    
    public String getIdent() {
        return _ident; 
    }
    
    
    /** 
     * Return true if we are connected to the given nodeid. 
     */
    public boolean isConnected(String nodeid) {
        return _wsNodes.isConnected(nodeid); 
    }
    
    
    
    public String getNodeId(String url) 
        throws URISyntaxException, IOException,InterruptedException  
    {
        if (url == null)
            return null; 
        RestClient parentapi = new RestClient(_api, url, "dbsync");
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
        RestClient parentapi = new RestClient(_api, url, "dbsync");
        String arg = ServerBase.serializeJson( new DbSyncApi.NodeInfo(_ident, items, url));
        HttpResponse res = parentapi.POST("/nodes", arg);
        if (res.statusCode() != 200) {
            _dbp.log().warn("DbSync", "Parent subscription failed. Status code: "+res.statusCode());
            return false; 
        }
        return true;
    }
    
    
    
    /**
     * Add node subscription. To database and to node list
     */
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
    
    
    /**
     * Remove subscription on this node. 
     */
    public void rmNode(String nodeid) {
        SyncDBSession db = null;
        try {           
            db = new SyncDBSession(_dbp.getDB(true)); // Autocommit on
            db.removeSyncPeer(nodeid);
            _nodes.remove(nodeid);
            _wsNodes.rmNode(nodeid);
            _parent.remove(nodeid);
            cancelNodeUpdates(db, nodeid);
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
    
    
    /**
     * Cancel pending updates to a node. For each update, act as if
     * an ack came from that node. This may lead to causal stability. 
     */
    private void cancelNodeUpdates(SyncDBSession db, String nodeid) 
        throws SQLException 
    {
        DbList<Sync.ItemUpdate> updates = db.getSyncUpdatesTo(nodeid);
        for (Sync.ItemUpdate x:updates) {
            // Create a fake ack 
            Sync.Ack ack = new Sync.Ack(x.origin, x.ts, false); 
            doAck(ack, x.sender); 
        }
    }
    
    
        
    /** 
     * Remove subscription on the parent node. 
     */
    public boolean rmNodeRemote(String id) 
        throws URISyntaxException, IOException, InterruptedException
    {
        var url = _parent.get(id);
        if (url==null)
            return true; 
        RestClient parentapi = new RestClient(_api, url, "dbsync");
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
            srv.setUserid("dbsync");
            _wsNodes.addServer(nodeid, srv);
            _parent.put(nodeid, url);
            _dbp.log().info("DbSync", "Parent (server) node registered: "+nodeid+", "+url);
        }
        _nodes.put(nodeid, items);
    }
    
    
    
    /**
     * Set up peer nodes to connect to. 
     * FIXME: This should be re-run if the DbSyncPeers table in database is updated. 
     */
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
    
    
    
    /** 
     * Get a websocket URL. 
     */
    private String wsUrl(String url) {
        return url.replaceFirst("http", "ws")
            .replaceFirst("/dbsync", "/ws/dbsync");
    }
    
    
    
    /**
     * Decide if update transaction should be performed or not. This is the
     * place for conflict resolution. By default we use a LWW strategy (last writer
     * wins). 
     */
    public boolean shouldCommit(SyncDBSession db, ItemUpdate upd, Handler hdl) 
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
        if (meta!=null && upd.ts <= meta.ts.getTime()) 
            return false; 
        
        /* Del wins: Ignore command if it is following a DEL */
        if (hdl.isDelWins() && meta!=null && "DEL".equals(meta.cmd))
            return false; 
        
        
        
        /* Update comes after a delete 
         * FIXME: It should be configurable if this is to be the resolution
         */
        if ((meta==null || meta.cmd.equals("DEL")) && upd.cmd.equals("UPD")) {
            _dbp.log().info("DbSync", "Convert UPD to ADD: "+upd.cid+", "+upd.itemid);
            upd.cmd="ADD";
        }
            
        /* Add comes after add or update 
         * FIXME: It should be configurable if this is to be the resolution 
         */
        if (meta!=null && meta.cmd.equals("ADD") && upd.cmd.equals("ADD")
            || (meta!=null && meta.cmd.equals("UPD") && upd.cmd.equals("ADD"))) {
            _dbp.log().info("DbSync", "Convert ADD to UPD: "+upd.cid+", "+upd.itemid);
            upd.cmd="UPD";
        }
        return true;
    }
 
    
    
    
    
    /**
     * Handle an incoming ack (from source) on a previous update. Ack consists of:        
     *   String origin; 
     *   long ts;
     *   boolean conf;
     *
     * It is a kind of two-phase commit protocol. The originator of the update collects ACK from 
     * the receiving nodes. When ACK is received from all, the orgininator decides that the update is 
     * causally stable and send a CONF message to all. 
     */
    public void doAck(Ack ack, String source) {
        SyncDBSession db = null;
        try {
            db = new SyncDBSession(_dbp.getDB());
            if (ack.conf) {
                _dbp.log().info("DbSync", "CONF received. Causal stability reached: ["+ack.origin+"], "+ack.ts);
                sendConf(db, ack, source);
                db.setStable(new java.util.Date(ack.ts));
            }
            else {
                /* 
                 * Remove sync updates for the origin and ts. 
                 * If there are no updates left we have received acks from all.
                 */
                db.removeSyncUpdatesFrom(ack.origin, new java.util.Date(ack.ts));
                if (!db.hasSyncUpdate(ack.origin, new java.util.Date(ack.ts))) 
                {
                    /* 
                     * If the ack was based on an incoming message. Pop it from the database 
                     * and find its sender. 
                     */
                    _dbp.log().info("DbSync", "ACK received from all: ["+ack.origin+"], "+ack.ts);
                    String sender = db.getSyncIncoming(ack.origin,  new java.util.Date(ack.ts));
                    db.removeSyncIncoming(ack.origin,  new java.util.Date(ack.ts));
                
                   /* 
                    * If sender is null, we are at the originating node. In that case we 
                    * have causal stability and can send a CONF to all peers. 
                    */
                    if (sender == null) {
                        _dbp.log().info("DbSync", "Causal stability reached: ["+ack.origin+"], "+ack.ts);
                        sendConf(db, ack, null);
                        db.setStable(new java.util.Date(ack.ts));
                    }
                    else
                        db.addSyncAck(sender, ack.origin, new java.util.Date(ack.ts), false); 
                }
            }
            db.commit();
        }
        catch (Exception e) {
            db.abort();
            _dbp.log().error("DbSync", "Exception: "+e.getMessage());  
            e.printStackTrace(System.out);
        }     
        finally {
            if (db != null) db.close();
        }
    }
    
    
    
  
    /**
     * Send an ack (confirmation) to all peers. Possibly with an exception.
     * To be used when stability is reached. 
     * To be used within a database transaction.
     */
    private void sendConf(SyncDBSession db, Ack ack, String except) 
        throws SQLException 
    {
        _dbp.log().debug("DbSync", "CONF to be sent to all peers "+ (except!=null ? "except "+except : "") + ": ["+ack.origin+"], "+ack.ts);
        ack.conf = true;
         for (String nodeid : _nodes.keySet())
            if (!nodeid.equals(except))
                db.addSyncAck(nodeid, ack.origin, new java.util.Date(ack.ts), true); 
    }
    
    
    
    /**
     * Queue an outgoing ack on a update. 
     * To be used within a database transaction.
     */
    private void sendAck(SyncDBSession db, ItemUpdate upd) 
        throws SQLException 
    {
        _dbp.log().info("DbSync", "ACK to be sent to "+upd.sender+": "+upd.cid+", "+upd.itemid+" ["+upd.origin+"], "+upd.ts);
         db.addSyncAck(upd.sender, upd.origin, new java.util.Date(upd.ts), false); 
    }
    
    
    
    
    /**
     * Perform an update. 
     * This is called when a update message is received from another node.
     * Return true if performed/committed or ignored due to LWW conflict resolution. False on error.
     */
    public boolean doUpdate(ItemUpdate upd, String source) 
    {  
        Handler hdl = _handlers.get(upd.cid);
        if (hdl==null) {
            _dbp.log().warn("DbSync", "Handler not found for: "+upd.cid);
            return false;
        }
       
       /* 
        * We need to propagate the update to the other peers. 
        * But not to node from where we got the message! 
        */
        propagate(upd, upd.sender);
            
        SyncDBSession db = null;
        try {
            db = new SyncDBSession(_dbp.getDB());
            if (!shouldCommit(db, upd, hdl)) {
                _dbp.log().info("DbSync", "Ignoring update for: "+upd.cid+", "+upd.itemid+" ["+upd.origin+"], "+upd.ts);
                db.abort();
                return true;
            }  
            /* Now, apply the update. 
             * First, update the metainfo in the database. 
             */
            db.setSync(upd.cid, upd.itemid, upd.cmd, new java.util.Date(upd.ts));
            hdl.handle(upd);

            if (!havePropagateNodes(upd.sender))
                sendAck(db, upd);
            else
                db.addSyncIncoming(source, upd.origin, new java.util.Date(upd.ts));
            
            db.commit(); 
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
        }
    }
    
 
 
 
    /**
     * Register (queue) a local update for synchronizing to other nodes. .
     * This is called from various update methods.  
     */
    long prev_ts = 0;
    public void localUpdate(String cid, String itemid, String userid, String cmd, String arg, boolean propagate) {
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
    
    public void localUpdate(String cid, String itemid, String userid, String cmd, String arg) {
        localUpdate(cid, itemid, userid, cmd, arg, true);
    }
    
    
    
    /* 
     * Return true if there are nodes to propagate to.
     */
    private boolean havePropagateNodes(String except) {
        return !(_nodes.isEmpty() || (_nodes.size() == 1 && _nodes.keySet().contains(except)));
    }
    
    
    /**
     * Propagate a update to other nodes. .
     */
    public void propagate(ItemUpdate upd, String except) 
    {
        _dbp.log().info("DbSync", "propagate: "+upd.origin+", "+upd.ts+", except: "+except);
        
        if (!upd.propagate || !havePropagateNodes(except))
            return;

        SyncDBSession db = null;
        try {
            db = new SyncDBSession(_dbp.getDB());
        
            /* 
             * If local (originating from this node) 
             * Update timestamp and op locally in the database for the item 
             */
            if (except==null)
                db.setSync(upd.cid, upd.itemid, upd.cmd, new java.util.Date()); 
            
            /* Queue message for updating peer nodes */
            if (!_nodes.isEmpty() && !(_nodes.keySet().size() == 1 && _nodes.keySet().contains(except))) 
                db.addSyncUpdate(upd);
            
            for (String nodeid : _nodes.keySet())
                if (upd.cid.matches(_nodes.get(nodeid)) && !nodeid.equals(except)) {   
                    _dbp.log().debug("DbSync", "Queueing outgoing update: "+upd.cid+", "+upd.itemid 
                        + ", "+ upd.cmd +", "+ TS(upd.ts) 
                        + (upd.origin.equals(_ident) ? "" : " ["+upd.origin + "]") + " to: " + nodeid);
                    db.addSyncUpdatePeer(nodeid, upd);
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
                db = new SyncDBSession(_dbp.getDB(false));
                for (Message msg : getMessagesTo(db, node)) {
                    msg.sender = _ident; 
 
                    /* Send each item to node */
                    if (!_wsNodes.put(node,  msg)) {
                        /* If post to node failed..*/
                        if (retr == null) 
                            retr = new Retry(6);
                        else { 
                            /* Double retry-time each retry, max 1 hour */
                            if (retr.time < 240)
                                retr.time = (retr.time * 2);
                            retr.cnt = retr.time;
                         }
                         
                        
                        _dbp.log().info("DbSync", "Failed sending message to node: "+node+" - rescheduling ("+retr.time*15+" s)");
                        _pending.put(node, retr);
                        break;
                    }
                    retr = null;
                    _dbp.log().info("DbSync", "Message to "+node+" succeeded ("+msg.mtype+")");
                    if (msg.mtype == MsgType.UPDATE) 
                        db.setSentSyncUpdates(node, new java.util.Date(msg.update.ts));
                    
                    else // ACK
                        db.removeSyncAcks(node, new java.util.Date(msg.ack.ts));
                }
                db.commit();
            }
                            
            catch (Exception e) {
                db.abort();
                _dbp.log().error("DbSync", "Exception: "+e.getMessage());  
                e.printStackTrace(System.out);
                continue;
            }     
            finally {
                if (db != null) db.close();
            }
                
        }
    }
    
    
    
    /*
     * Create a list of messages to be sent to a peer node. 
     */
    private List<Message> getMessagesTo(SyncDBSession db, String node)
        throws SQLException
    {
        DbList<ItemUpdate> updates = db.getSyncUpdatesTo(node);
        DbList<Ack> acks = db.getSyncAcksTo(node);
        List<Message> msgs = new ArrayList<Message>();
        if (updates!=null) 
        for (ItemUpdate u : updates)
            msgs.add(new Message(u));
        if (acks!=null) 
        for (Ack a : acks)
            msgs.add(new Message(a));
        return msgs;
    }
 
    
    /**
     * Register a cid (dataset) with a handler. 
     */
    public void addCid(String cid, Handler hdl) {
        _handlers.put(cid, hdl);
    }
    

}
