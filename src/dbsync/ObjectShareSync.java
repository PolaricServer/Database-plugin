
package no.polaric.aprsdb.dbsync;
import no.polaric.aprsdb.*;
import no.polaric.aprsd.*;
import no.polaric.aprsd.http.*;
import no.polaric.aprsdb.http.SignsApi;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import no.polaric.aprsd.filter.*;
import java.util.stream.Collectors;
import  java.sql.*;
import  javax.sql.*;
import java.net.http.*;


 
public class ObjectShareSync implements Sync.Handler
{
    private ServerAPI _api;   
    private PluginApi _dbp;
    private PubSub _psub;
   
   
    public ObjectShareSync(ServerAPI api, PluginApi dbp) 
    {
        _api=api; _dbp=dbp;
        _psub = (no.polaric.aprsd.http.PubSub) _api.getWebserver().getPubSub();
    }
   
    public boolean isDelWins() {return false;}
   
   
    /**
     * Handle an update from other node. 
     * Arguments: 
     *  upd - ItemUpdate structure: 
     *      cmd    - Command as string (in this case: 'ADD', 'UPD' (update) or 'DEL' (delete). 
     *      arg    - JSON encoded argument (application dependent). Typically the state of the item to be updated.
     *               in this case it is a JsObject.User object. 
     *      userid - String. User that initiated the change.
     *      itemid - String. Some identifier for the item to be updated (in the case of ADD, it is the tag). 
     */
     
    public void handle(Sync.ItemUpdate upd)
        throws DBSession.SessionError
    {  
        
        if ("ADD".equals(upd.cmd)) {
            JsObject.User uu = (JsObject.User) ServerBase.fromJson(upd.arg, JsObject.User.class);
            _add(uu, upd.userid, upd.itemid); 
        }
        else if ("DEL".equals(upd.cmd)) {
            _del(upd.itemid, upd.userid, upd.arg); 
        }
        else
           // error("Unknown command");
           ;
    }
   
   
    
   
    private void _add(JsObject.User u, String userid, String id) 
        throws DBSession.SessionError
    {
        MyDBSession db = _dbp.getDB();
        try {
            if (id.matches("_ALL_@.*")) { 
                String[] x = id.split("@", 2);
                db.shareJsObjects(x[1], "_ALL_", u.userid, u.readOnly);
            }
            else {
                db.shareJsObject(id, userid,  u.userid, u.readOnly);
            }
            _psub.put("sharing", id, userid);
            db.commit();
        }      
        catch(Exception e) {
            db.abort();
            _dbp.log().error("SignsSync", "Exception: "+e);
            e.printStackTrace(System.out);
            return;
        } 
        finally { db.close(); }
    }
     
     
     
     
    private void _del(String id, String u, String userid) 
        throws DBSession.SessionError
    {
        MyDBSession db = _dbp.getDB();
        try {
            if (id.matches("_ALL_@.*")) { 
                String[] x = id.split("@", 2);
                db.unlinkJsObjects(x[1], u, userid);
            }
            else {
                db.unlinkJsObject(id, u, userid);
            }
            _psub.put("sharing", id, userid);
            db.commit();
        }      
        catch(Exception e) {
            db.abort();
            _dbp.log().error("SignsSync", "Exception: "+e.getMessage());
            return;
        } 
        finally { db.close(); }
    }
}
