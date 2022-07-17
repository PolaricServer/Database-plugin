
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


 
public class UserSync implements Sync.Handler
{
    private ServerAPI _api;   
    private PluginApi _dbp;
   
    private UserDb _users; 
   
   
   
    public UserSync(ServerAPI api, PluginApi dbp) 
    {
        _api=api; _dbp=dbp;
        _users = (UserDb) api.getWebserver().getUserDb();
    }
   
   
   
    /**
     * Handle an update from other node. 
     * Arguments: 
     *  upd - ItemUpdate structure: 
     *      cmd    - Command as string (in this case: 'ADD', 'UPD' (update) or 'DEL' (delete). 
     *      arg    - JSON encoded argument (application dependent). Typically the state of the item to be updated 
     *               (from REST API). In this case it is a UserApi.UserInfo or UserApi.UserUpdate object. 
     *      userid - String. User that initiated the change.
     *      itemid - String. Some identifier for the item to be updated. In this case it is the userid. 
     *
     * Note: Since updates contain password and authorisations, it is important that sessions are 
     * secure and encrypted!  
     */
     
    public void handle(Sync.ItemUpdate upd)
        throws DBSession.SessionError
    {
        if ("ADD".equals(upd.cmd)) {
            UserApi.UserInfo si = (UserApi.UserInfo) ServerBase.fromJson(upd.arg, UserApi.UserInfo.class);
            if (si==null)
                _dbp.log().error("UsersSync", "UserInfo deserialisation failed");
            else 
                _add(si, upd.userid, upd.itemid);
        }
        else if ("UPD".equals(upd.cmd)) {
            UserApi.UserUpdate si = (UserApi.UserUpdate) ServerBase.fromJson(upd.arg, UserApi.UserUpdate.class);
            if (si==null)
                _dbp.log().error("UserSync", "UserUpdate deserialisation failed");
            else
                _upd(upd.itemid, si, upd.userid);
        }
        else if ("DEL".equals(upd.cmd)) 
            _del(upd.itemid);
        else
           // error("Unknown command");
           ;
    }
   

    /* Add user */
    private void _add(UserApi.UserInfo u, String userid, String id) 
        throws DBSession.SessionError
    {
        if (_users.get(id) != null)
            /* 
             * User exists already. From before this update.
             * The simplest approach is to delete the previous user first.
             */
            _dbp.log().warn("UserSync", "Add user: User "+id+" exists - deleting");
            _users.remove(id); 
                
        /* Now, add the user */
        _users.add(id, u.name, u.sar, u.admin, u.suspend, u.passwd, u.group);
    }
     
     
     
    private void _upd(String ident, UserApi.UserUpdate sc, String userid)
        throws DBSession.SessionError
    {        
        User u = _users.get(ident);
        if (u==null) {
            _dbp.log().warn("UserSync", "Update user: non-existent, creating: "+ident);;
            u = _users.add(ident);
        }    
        if (sc.group != null) {
            Group g = _users.getGroupDb().get(sc.group);
            if (g==null)
               _dbp.log().error("UserSync", "Update user: Unknown group: "+sc.group);
            else
                u.setGroup(g);
        }    
        if (sc.name != null)
            u.setName(sc.name);           
        if (sc.callsign != null)
            u.setCallsign(sc.callsign);
        if (sc.passwd != null) 
            u.setPasswd(sc.passwd);    
        u.setSar(sc.sar);
        u.setAdmin(sc.admin);
        u.setSuspended(sc.suspend);
    }
     
     
     
    private void _del(String ident) 
        throws DBSession.SessionError
    {
        _users.remove(ident); 
    }
}
