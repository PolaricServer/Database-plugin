
package no.polaric.aprsdb;
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
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.annotation.JsonIgnore;
 
public interface Sync
{
    
    /* Update message */    
    public static class ItemUpdate {
        public String cid, itemid;
        public long ts; 
        public String userid;
        public String cmd; // ADD, DEL, UPD
        public String mac;
        
      //  @JsonRawValue
        public String arg;

        @JsonIgnore
        public String generateMac(ServerAPI api) {
            String key = api.getProperty("system.auth.key", "NOKEY");
            return SecUtils.xDigestB64(key + cid + itemid + cmd + ts, 24);
        }
        
    
        public ItemUpdate() {};
        public ItemUpdate(String c, String i, String u, String cm, String a) {
            cid=c; itemid=i;  userid=u; cmd=cm; arg=a;
            ts=(new java.util.Date()).getTime(); 
        }
        public ItemUpdate(String c, String i, String u, String cm, String a, long t) {
             cid=c; itemid=i; userid=u; cmd=cm; arg=a;
             ts = t;
        }
        
    }
    
    
    /* 
     * We need to provide handler classes for each type/group of information to synchronize 
     * This usually (?) corresponds to a database table. We also need to chose a name for each
     * instance of this that is registered, the cid (collection id). 
     */
    public interface Handler {
        public void handle(ItemUpdate upd)
            throws DBSession.SessionError;
    }
    
    
    /**
     * Perform an update. 
     * This is called from REST API impl. when a POST request is received. 
     */
    public boolean doUpdate(ItemUpdate upd);
 
    /**
     * Queue a local update for synchronizing to other nodes. .
     * This is called from various update methods.  
     */
    public void localUpdate(String cid, String itemid, String userid, String cmd, String arg);
 
 
    /**
     * Register a handler with a cid. 
     */
    public void addCid(String cid, Handler hdl);
    

}
