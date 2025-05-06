
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

    enum MsgType {UPDATE, ACK};
    
    /* Generic message - can be update or ack */
    public static class Message {
        public MsgType mtype;
        public String sender;
        public ItemUpdate update;
        public Ack ack;
        
        public Message() {}
        public Message(ItemUpdate u) {
            mtype = MsgType.UPDATE;
            update = u;
        }
        public Message(Ack a) {
            mtype = MsgType.ACK;
            ack = a;
        }
    }
    
    /* Ack message - confirm that update message has been performed */
    public static class Ack {
        public String origin; 
        public long ts;
        public boolean conf;
        
        public Ack() {}
        public Ack(String or, long t) {
            origin=or; ts=t; conf=false; 
        }
        public Ack(String or, long t, boolean cnf) {
            origin=or; ts=t; conf=cnf; 
        }
    }
    
    
    /* Update message */    
    public static class ItemUpdate {
        public String cid, itemid;
        public long ts; 
        public String userid;
        public String cmd; // ADD, DEL, UPD
        public boolean propagate = true;
        public String origin;
        public String sender; 
        
      //  @JsonRawValue
        public String arg;
        
        @JsonIgnore
        public void stopPropagate() {
            propagate=false;
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
        public ItemUpdate(String c, String i, String u, String cm, String a, long t, String org) {
             cid=c; itemid=i; userid=u; cmd=cm; arg=a; origin=org;
             ts = t;
        }
    }
    
    
    /* 
     * We need to provide handler classes for each type/group of information to synchronize. 
     * This usually (?) corresponds to a database table. We also need to chose a name for each
     * instance of this that is registered, the cid (collection id). 
     */
    public interface Handler {
        
        public boolean isDelWins(); 
        
        public void handle(ItemUpdate upd)
            throws DBSession.SessionError;
            
        default public void onDelete(String ident) { }
    }
    
    
    /**
    /* Get a handler registered for the given cid. 
     */
    public Handler getHandler(String cid); 
    
    
    /**
     * Perform an update. 
     * This is called from REST API impl. when a POST request is received. 
     */
    public boolean doUpdate(ItemUpdate upd, String src);
 
 
    /**
     * Queue a local update for synchronizing to other nodes. .
     * This is called from various update methods.  
     */
    public void localUpdate(String cid, String itemid, String userid, String cmd, String arg);
    
    public void localUpdate(String cid, String itemid, String userid, String cmd, String arg, boolean propagate);
 
 
    /**
     * Register a handler with a cid. 
     */
    public void addCid(String cid, Handler hdl);
    

}
