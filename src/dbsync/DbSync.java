
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
           
    
    /* Peer node to synchronize data with */
    public static class Peer {
        public RestClient http;
        public String cfilter; /* Regex */
        public int cnt = 0;
        
        Peer(ServerAPI api, String u, String cf) 
            { http = new RestClient(api, u, true); 
              cfilter=cf;}
    }
    
    
    public DbSync(ServerAPI api) {
        _api = api;
        _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
        
        _dbp.log().info("DbSync", "Initializing replication (eventual/partial consistency)");
     
        /* Schedule periodic task */
        hb.schedule( new TimerTask() 
            { public void run() {       
                updatePeers();
            } 
        } , 40000, 20000); 
        
     
        /* Set up of peer nodes */
        int npeers = api.getIntProperty("db.sync.npeers", 0);
        for (int i=1; i<=npeers; i++) {
            String url = api.getProperty("db.sync.peer"+i+".url", "");
            String filt = api.getProperty("db.sync.peer"+i+".filter", "");
            Peer p = new Peer(_api, url, filt);
            _peers.add(p);
            _dbp.log().info("DbSync", "Peer node registered: "+url);
        }
    }
        
 
    
    /**
     * Perform an update. 
     * This is called from REST API impl. when a POST request is received. 
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
            
            java.util.Date ts = db.getSync(upd.cid, upd.itemid);
            if (ts!=null && ts.getTime() > upd.ts) {
                /* If timestamp is older than last local update, ignore the update */
                _dbp.log().info("DbSync", "Ignoring incoming update for: "+upd.cid+":"+upd.itemid);
                db.abort();
                return true;
            }
            _dbp.log().info("DbSync", "Handling incoming update for: "+upd.cid+":"+upd.itemid);        
            db.setSync(upd.cid, upd.itemid, new java.util.Date());
            db.commit(); 
            
            /* FIXME: Maybe this should be part of the transaction - it may fail */
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
        }
    }
    
 
//  
    /**
     * Queue a local update for synchronizing to other nodes. .
     * This is called from various update methods.  
     */
    public void localUpdate(String cid, String itemid, String userid, String cmd, String arg) 
    {
        if (_peers.isEmpty()) {
           _dbp.log().info("DbSync", "Outgoing update for: "+cid+":"+itemid+" - no peers");
            return;
        }
        
        
        ItemUpdate upd = new ItemUpdate(cid, itemid, userid, cmd, arg);
        _dbp.log().info("DbSync", "Queueing outgoing update for: "+cid+":"+itemid);
        
        SyncDBSession db = null;
        try {
            db = new SyncDBSession(_dbp.getDB());
        
            /* Update timstamp locally for the item */
            db.setSync(cid, itemid, new java.util.Date()); 
            
            /* Queue message for updating peers */
            for (Peer p : _peers)
                if (cid.matches(p.cfilter)) 
                    db.addSyncUpdate(p.http.getUrl(), upd);
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
                    upd.mac = upd.generateMac(_api);
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
