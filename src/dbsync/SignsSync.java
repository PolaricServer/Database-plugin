
package no.polaric.aprsdb.dbsync;
import no.polaric.aprsdb.*;
import no.polaric.aprsd.*;
import no.polaric.aprsd.http.*;
import no.polaric.aprsdb.http.SignsApi;
import uk.me.jstott.jcoord.*; 
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import no.polaric.aprsd.filter.*;
import java.util.stream.Collectors;
import  java.sql.*;
import  javax.sql.*;
import java.net.http.*;


 
public class SignsSync implements Sync.Handler
{
    private ServerAPI _api;   
    private PluginApi _dbp;
    private PubSub _psub;
   
   
    public SignsSync(ServerAPI api, PluginApi dbp) 
    {
        _api=api; _dbp=dbp;    
        _psub = (no.polaric.aprsd.http.PubSub) _api.getWebserver().getPubSub();
    }
   
   
   
    /**
     * Handle an update from other node. 
     * Arguments: 
     *  upd - ItemUpdate structure: 
     *      cmd    - Command as string (in this case: 'ADD', 'UPD' (update) or 'DEL' (delete). 
     *      arg    - JSON encoded argument (application dependent). Typically the state of the item to be updated.
     *               in this case it is a SignsApi.SignInfo object. 
     *      userid - String. User that initiated the change.
     *      itemid - String. Some identifier for the item to be updated (in the case of ADD, the item that contain the 
     *               item to be added). 
     */
     
    public void handle(Sync.ItemUpdate upd)
        throws DBSession.SessionError
    {
        if ("ADD".equals(upd.cmd)) {
            SignsApi.SignInfo si = (SignsApi.SignInfo) ServerBase.fromJson(upd.arg, SignsApi.SignInfo.class);
            if (si==null)
                _dbp.log().error("SignsSync", "SignInfo deserialisation failed");
            else 
                _add(si, upd.userid, upd.itemid);
        }
        else if ("UPD".equals(upd.cmd)) {
            SignsApi.SignInfo si = (SignsApi.SignInfo) ServerBase.fromJson(upd.arg, SignsApi.SignInfo.class);
            if (si==null)
                _dbp.log().error("SignsSync", "SignInfo deserialisation failed");
            else
                _upd(upd.itemid, si, upd.userid);
        }
        else if ("DEL".equals(upd.cmd)) 
            _del(upd.itemid, upd.userid);
        else
           // error("Unknown command");
           ;
    }
   
   
    
    /*
     * We assign a unique id to the sign when it is created. When it is propagated we need to make sure that
     * replicas are created with the same id. IF we juse just numbers from a sequence as id and if these should be
     * globally unique and have a total order, we have a problem. 
     *
     * When receiving a update we may update the next value of the sequence with the received id. 
     * There may still be conflicts since propagation can have unpredictable delays. 
     *
     * when receiving an id: 
     * if (id <= getSeqCurr("signs_seq"))
     *       CONFLICT; 
     *       
     * Since we doesn't need a totally ordered numeric id, we add the id of the server. If we still update the 
     * next value for the numeric part, we get a partial order corresponding to a causal order (Lamport clock). 
     */
   
    private void _add(SignsApi.SignInfo sc, String userid, String id) 
        throws DBSession.SessionError
    {
        SignsDBSession db = new SignsDBSession(_dbp.getDB());
        try {
            Reference ref = new LatLng(sc.pos[1], sc.pos[0]);
            
            String[] iid = id.split("@");
            
            db.setSeqNext("signs_seq", Integer.parseInt(iid[0]) );
            db.addSign(iid[1], sc.scale, sc.icon, sc.url, sc.descr, ref, sc.type, userid);       
            _psub.put("sign", null, userid);
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
     
     
     
    private void _upd(String ident, SignsApi.SignInfo sc, String userid)
        throws DBSession.SessionError
    {        
        SignsDBSession db = new SignsDBSession(_dbp.getDB());
        try {
            Reference ref = new LatLng(sc.pos[1], sc.pos[0]);         
            Sign s = db.getSign(sc.id);
            if (s==null) {
                _dbp.log().warn("SignsSync", "Replica object doesn't exist. Adding: "+sc.id);
                db.addSignIdent(sc.id, sc.scale, sc.icon, sc.url, sc.descr, ref, sc.type, userid);
            }
            else
                db.updateSign(sc.id, sc.scale, sc.icon, sc.url, sc.descr, ref, sc.type, userid);        
            _psub.put("sign", null, userid);
            db.commit();
        }
        catch(Exception e) {
            db.abort();
            _dbp.log().error("SignsSync", "Exception: "+e.getMessage());
            return;
        }
        finally { db.close();}  
    }
     
     
     
    private void _del(String ident, String userid) 
        throws DBSession.SessionError
    {
        SignsDBSession db = new SignsDBSession(_dbp.getDB());
        try {
            db.deleteSign(ident);   
            _psub.put("sign", null, userid);
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
