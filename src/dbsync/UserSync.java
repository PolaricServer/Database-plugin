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
import no.arctic.core.*;
import no.arctic.core.httpd.*;
import no.arctic.core.auth.*;
import no.polaric.aprsdb.*;
import no.polaric.aprsd.*;
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
    private PluginApi _dbp;
    private UserDb _users; 
    private PubSub _psub;
   
   
    public UserSync(ServerConfig conf, PluginApi dbp) 
    {
        _dbp=dbp;
        _users = (UserDb) conf.getWebserver().userDb();
        _psub = (PubSub) conf.getWebserver().pubSub();
        _psub.createRoom("userdb", null);
    }
   
   
    public boolean isDelWins() {return false;}
   
   
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
        if (_users.get(id) != null) {
            /* 
             * User exists already. From before this update.
             * The simplest approach is to delete the previous user first.
             */
            _dbp.log().warn("UserSync", "Add user: User "+id+" exists - deleting");
            _users.remove(id); 
        }
                
        /* Now, add the user */
        if (_users.getGroupDb().get(u.group) == null) {
            _dbp.log().warn("UserSync", "Update user: Unknown group: "+u.group+", using 'DEFAULT'");
            u.group = "DEFAULT";
        }    
        _users.add(id, u.name, u.admin, u.suspend, u.passwd, u.group);
        if (_psub != null)
            _psub.put("userdb", null, null);
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
        if (sc.group != null) {
            Group g = _users.getGroupDb().get(sc.altgroup);
            if (g==null)
               _dbp.log().error("UserSync", "Update user: Unknown group: "+sc.altgroup);
            else
                u.setAltGroup(g);
        }
        if (sc.name != null)
            u.setName(sc.name);           
        if (sc.callsign != null)
            u.setCallsign(sc.callsign);
        if (sc.passwd != null) 
            u.setPasswd(sc.passwd);    
        u.getGroup().setOperator(sc.operator);
        u.setAdmin(sc.admin);
        u.setSuspended(sc.suspend);
        if (_psub != null)
            _psub.put("userdb", null, null);
    }
     
     
     
    private void _del(String ident) 
        throws DBSession.SessionError
    {
        _users.remove(ident); 
    }
    
    
    
    /* 
     * Handle deletions. Deal with referential integrity. 
     */
    public void onDelete(String userid)
    {
        SignsDBSession db = null;
        try {
            db = new SignsDBSession(_dbp.getDB());
            DbList<String> deleted = db.getSignsByUser(userid);
            db.deleteSignsByUser(userid);
            db.commit();
            for (String x: deleted)
                _dbp.getSync().localUpdate("signs", x, "_system_", "DEL", null, false);
            if (_psub != null)
                _psub.put("userdb", null, null);
        }
        catch(Exception e) {
            if (db != null) db.abort();
            _dbp.log().error("ClientUserSyncer", "Exception: "+e.getMessage());   
            e.printStackTrace(System.out);
        }
        finally {
            if (db != null) db.close();
        }
    }
    
    
    
}
