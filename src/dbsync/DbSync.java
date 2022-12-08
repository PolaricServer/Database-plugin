
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
import  java.sql.*;
import  javax.sql.*;
import java.net.http.*;
import static spark.Spark.*;


/*
 * Replication with relaxed/eventual consistency. 
 */
 
public class DbSync implements Sync
{
    private ServerAPI _api;   
    private PluginApi _dbp;
    private Map<String, Handler> _handlers = new HashMap<String, Handler>();
    private Timer hb = new Timer();
    private DuplicateChecker _dup = new DuplicateChecker(300);
    private String _ident;
    
    
    /* Websocket connected nodes */
    private NodeWsApi<ItemUpdate> _wsNodes;
    /* Configured nodes */
    private Map<String, String> _nodes = new HashMap<String, String>();
    /* Nodes on wait */
    private Map<String, Integer> _pending = new HashMap<String, Integer>();       
    
    
    
    public DbSync(ServerAPI api) {
        _api = api;
        _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
        
        _dbp.log().info("DbSync", "Initializing replication (eventual consistency)");
     
        
        hb.schedule( new TimerTask() 
            { public void run() {       
                updatePeers2();
            } 
        } , 45000, 20000); 
        
        
     
        /* Set up of ident for this node. Use mycall as default */
        String mycall = api.getProperty("default.mycall", "NOCALL").toUpperCase();
        _ident = api.getProperty("db.sync.ident", mycall);
        
        
        /* Setup of servers for websocket comm. */
        NodeWs ws = new NodeWs(_api, null);
        webSocket("/dbsync", ws);
        
        _wsNodes = new NodeWsApi(_ident, ws, ItemUpdate.class);
        _wsNodes.setHandler( (nodeid, upd)-> {
                if (upd==null)
                    _dbp.log().warn("DbSync", "Received ItemUpdate: "+nodeid+", NULL");
                else
                    _dbp.log().info("DbSync", "Received ItemUpdate: "+nodeid+", "+upd.cid+", "+upd.itemid);
                doUpdate((ItemUpdate) upd);
            } 
        );

        
        int npeers = api.getIntProperty("db.sync.nnodes", 0);
        for (int i=1; i<=npeers; i++) {
            String id = api.getProperty("db.sync.node"+i+".ident", null);
            if (id==null) {
                _dbp.log().warn("DbSync", "Server node "+i+": No ident. Ignoring");
                continue;
            }
            String url = api.getProperty("db.sync.node"+i+".url", null);
            String filt = api.getProperty("db.sync.node"+i+".filter", ".*");
                
            /* If URL is given, add server to websocket inferface */
            if (url != null) {
                NodeWsClient srv = new NodeWsClient(_api, id, url, true);
                _wsNodes.addServer(id, srv);
                _dbp.log().info("DbSync", "Server node registered: "+id+", "+url);
            }
            else
                _dbp.log().info("DbSync", "Node registered: "+id);
            
            /* Add configured node */
            _nodes.put(id, filt);
        }
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
         * we set the propagate flag to false.
         */
        if (_dup.contains(upd.origin+upd.ts)) {
            upd.propagate = false;
            return false;
        }
        else
            _dup.add(upd.origin+upd.ts);
        
        SyncDBSession.SyncOp meta = db.getSync(upd.cid, upd.itemid);
        if (meta!=null && upd.ts <= meta.ts.getTime())
            return false; 

        if ((meta==null || meta.cmd.equals("DEL")) && upd.cmd.equals("UPD")) {
            _dbp.log().info("DbSync", "Convert UPD to ADD: "+upd.cid+":"+upd.itemid);
            upd.cmd="ADD";
        }
            
        if (meta!=null && meta.cmd.equals("ADD") && upd.cmd.equals("ADD")
            || (meta!=null && meta.cmd.equals("UPD") && upd.cmd.equals("ADD"))) {
            _dbp.log().info("DbSync", "Convert ADD to UPD: "+upd.cid+":"+upd.itemid);
            upd.cmd="UPD";
        }
        return true;
    }
 
    
    
    /**
     * Perform an update. 
     * This is called from REST API impl (DbSyncApi). when a POST request is received, 
     * i.e. when a update is received from another node.
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
                _dbp.log().info("DbSync", "Ignoring incoming update for: "+upd.cid+":"+upd.itemid+" ["+upd.origin+"]");
                db.abort();
                return true;
            }
            _dbp.log().debug("DbSync", "Handling incoming update for: "+upd.cid+":"+upd.itemid+" ["+upd.origin+"]");        
            db.setSync(upd.cid, upd.itemid, upd.cmd, new java.util.Date());
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
    
 

 
    public void localUpdate(String cid, String itemid, String userid, String cmd, String arg) {
        ItemUpdate upd = new ItemUpdate(cid, itemid, userid, cmd, arg);
        upd.origin = _ident;
        propagate(upd, null); 
    }
    
    
    
    /**
     * Register (queue) a local update for synchronizing to other nodes. .
     * This is called from various update methods.  
     */
    public void propagate(ItemUpdate upd, String except) 
    {
        if (!upd.propagate)
            return;
        if (_nodes.isEmpty()) {
           _dbp.log().info("DbSync", "Outgoing update for: "+upd.cid+":"+upd.itemid+" - no nodes");
            return;
        }

        SyncDBSession db = null;
        try {
            db = new SyncDBSession(_dbp.getDB());
        
            /* Update timstamp locally for the item */
            db.setSync(upd.cid, upd.itemid, upd.cmd, new java.util.Date()); 
            
            /* Queue message for updating peer nodes */
            for (String nodeid : _nodes.keySet())
                if (upd.cid.matches(_nodes.get(nodeid)) && !nodeid.equals(except)) {   
                    _dbp.log().info("DbSync", "Queueing outgoing update for: "+upd.cid+":"+upd.itemid + 
                        " ["+upd.origin + "] to: "+nodeid);
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
     * This is called periodically. 20 second interval. It attempts to POST the updates
     * to the peer nodes. If request succeeds, it is deleted from the queue. If not, it 
     * is rescheduled and we will retry after a while. 
     */

    protected void updatePeers2()
    {
        for (String node: _wsNodes.getNodes()) {
 
            Integer delay = _pending.get(node);
            _pending.remove(node);
            if (delay != null && delay > 0) {
                delay--;
                _pending.put(node, delay);
                continue;
            }
            
            SyncDBSession db = null;
            try {
                /* Search database for items to be sent to node */
                db = new SyncDBSession(_dbp.getDB(true)); // Autocommit on
                DbList<ItemUpdate> msgs = db.getSyncUpdates(node);
                for (ItemUpdate upd : msgs) {
                    upd.sender = _ident;
                    if (_nodes.get(node)==null) {
                        _dbp.log().warn("DbSync", "Attempt to post to unknown node: "+node+" - ignoring");
                        break;
                    }
                    /* Send each item to node */
                    if (!_wsNodes.put(node, upd)) {
                        /* If post to node failed..*/
                        _dbp.log().info("DbSync", "Post failed to node: "+node+" - rescheduling");
                        _pending.put(node, 6);
                        break;
                    }
                    _dbp.log().debug("DbSync", "Post to "+node+" succeeded ("+upd.cid+")");
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
