
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



/*
 * Replication with relaxed/eventual consistency. 
 */
 
public class DbSync implements Sync
{
    private ServerAPI _api;   
    private PluginApi _dbp;
    private Map<String, Handler> _handlers = new HashMap<String, Handler>();
    private List<Peer> _peers = new ArrayList();
    private Timer hb = new Timer();
    private DuplicateChecker _dup = new DuplicateChecker(300);
    private String _ident;
           
    
    /* Peer node to synchronize data with */
    public static class Peer {
        /* Callsign or other identifier */
        public String ident; 
        
        /* REST service */
        public RestClient http;
        
        /* Regex filter for selecting CRDT instances to synchronise */
        public String cfilter;
        
        /* Wait count before next trial */
        public int cnt = 0;
        
        Peer(ServerAPI api, String id, String u, String cf) 
            { http = new RestClient(api, u, true); 
              ident = id;
              cfilter=cf;}
    }
    
    
    
    public DbSync(ServerAPI api) {
        _api = api;
        _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
        
        _dbp.log().info("DbSync", "Initializing replication (eventual consistency)");
     
        /* Schedule periodic task */
        hb.schedule( new TimerTask() 
            { public void run() {       
                updatePeers();
            } 
        } , 40000, 20000); 
        
     
        /* Set up of ident for this node. Use mycall as default */
        String mycall = api.getProperty("default.mycall", "NOCALL").toUpperCase();
        _ident = api.getProperty("db.sync.ident", mycall);

        /* Setup of peers */
        int npeers = api.getIntProperty("db.sync.npeers", 0);
        for (int i=1; i<=npeers; i++) {
            String id = api.getProperty("db.sync.peer"+i+".ident", null);
            if (id==null) {
                _dbp.log().warn("DbSync", "Peer node "+i+": No ident. Ignoring");
                continue;
            }
            String url = api.getProperty("db.sync.peer"+i+".url", null);
            if (url==null) {
                _dbp.log().warn("DbSync", "Peer node "+i+": No url. Ignoring");
                continue;
            }
            String filt = api.getProperty("db.sync.peer"+i+".filter", "");
            Peer p = new Peer(_api, id, url, filt);
            _peers.add(p);
            _dbp.log().info("DbSync", "Peer node registered: "+id+", "+url);
        }
    }
        
        
        
    public Peer url2peer(String url) {
        for (Peer x: _peers)
            if (x.http.getUrl().equals(url))
                return x;
        return null;
    }
    
    
    public Peer ident2peer(String id) {
        for (Peer x: _peers)
            if (x.ident.equals(id))
                return x;
        return null;
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
            _dbp.log().info("DbSync", "Handling incoming update for: "+upd.cid+":"+upd.itemid+" ["+upd.origin+"]");        
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
        if (_peers.isEmpty()) {
           _dbp.log().info("DbSync", "Outgoing update for: "+upd.cid+":"+upd.itemid+" - no peers");
            return;
        }

        SyncDBSession db = null;
        try {
            db = new SyncDBSession(_dbp.getDB());
        
            /* Update timstamp locally for the item */
            db.setSync(upd.cid, upd.itemid, upd.cmd, new java.util.Date()); 
            
            /* Queue message for updating peer nodes */
            for (Peer p : _peers)
                if (upd.cid.matches(p.cfilter) && !p.ident.equals(except)) {   
                    _dbp.log().info("DbSync", "Queueing outgoing update for: "+upd.cid+":"+upd.itemid+" ["+upd.origin
                       + "] to: "+p.http.getUrl());
                    db.addSyncUpdate(p.http.getUrl(), upd);
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
     *
     * FIXME: Can we put more than one update in the same POST? 
     * FIXME: Should mac be a part of the update? 
     * FIXME: Could peers be updated in parallel?
     */
    protected void updatePeers() 
    {
        for (Peer p : _peers) {
            if (p.cnt > 0) {
                p.cnt--;
                continue;
            }
        
            SyncDBSession db = null;
            try {
                db = new SyncDBSession(_dbp.getDB(true)); // Autocommit on
                DbList<ItemUpdate> msgs = db.getSyncUpdates(p.http.getUrl());
                for (ItemUpdate upd : msgs) {
                    upd.sender = _ident;
                    HttpResponse res = p.http.POST("dbsync", ServerBase.toJson(upd) ); 
                    if (res==null) {
                        _dbp.log().info("DbSync", "Post to "+p.http.getUrl()+" failed. Rescheduling..");
                        p.cnt = 60; // 20 minutes   
                        break;
                    }
                    if (res.statusCode() != 200) {
                        _dbp.log().info("DbSync", "Post to "+p.http.getUrl()+" failed with code="+res.statusCode()+". Rescheduling..");
                        p.cnt = 60; // 20 minutes
                        break;
                    }
                    _dbp.log().info("DbSync", "Post to "+p.http.getUrl()+" succeeded ("+upd.cid+").");
                    db.removeSyncUpdates(p.http.getUrl(), new java.util.Date(upd.ts+1000));
                }
            }    
            catch(Exception e) {
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
