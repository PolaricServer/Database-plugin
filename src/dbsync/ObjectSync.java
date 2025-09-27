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


 
public class ObjectSync implements Sync.Handler
{
    private ServerConfig _api;   
    private PluginApi _dbp;
    private PubSub _psub;
   
   
    public ObjectSync(ServerConfig api, PluginApi dbp) 
    {
        _api=api; _dbp=dbp;
        _psub = (PubSub) _api.getWebserver().pubSub();
    }
   
     public boolean isDelWins() {return false;}
   
    /**
     * Handle an update from other node. 
     * Arguments: 
     *  upd - ItemUpdate structure: 
     *      cmd    - Command as string (in this case: 'ADD', 'UPD' (update) or 'DEL' (delete). 
     *      arg    - JSON encoded argument (application dependent). Typically the state of the item to be updated.
     *               in this case it is a raw JSON object. 
     *      userid - String. User that initiated the change.
     *      itemid - String. Some identifier for the item to be updated (in the case of ADD, it is the tag). 
     */
     
    public void handle(Sync.ItemUpdate upd)
        throws DBSession.SessionError
    {
        String[] id = upd.itemid.split(":"); 
        if ("ADD".equals(upd.cmd)) {
            _add(id[1], upd.arg, upd.userid, id[0]); 
        }
        else if ("UPD".equals(upd.cmd)) {
            _upd(id[1], upd.arg, upd.userid, id[0]);
        }
        else if ("DEL".equals(upd.cmd)) {
            _del(id[1], upd.userid, id[0]); 
        }
        else
           // error("Unknown command");
           ;
    }
   
   
    /*
     * We assign a unique id to the object when it is created. When it is propagated we need to make sure that
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
   
    private void _add(String ident, String data, String userid, String tag) 
        throws DBSession.SessionError
    {
        MyDBSession db = _dbp.getDB();
        try {
            db.addJsObjectIdent(ident, userid, tag, data);
            _psub.put("object", tag, userid);
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
     
     
     
    private void _upd(String ident, String data, String userid, String tag)
        throws DBSession.SessionError
    {        
        MyDBSession db = _dbp.getDB();
        try {
            if (db.haveJsObject(ident))            
                db.updateJsObject(ident, data);
            else {
                _dbp.log().warn("ObjectSync", "Replica object doesn't exist. Adding: "+ident);
                db.addJsObjectIdent(ident, userid, tag, data);
                // FIXME: Also add sharings? ? ? `
            }
            _psub.put("object", tag, userid);
            db.commit();
        }
        catch(Exception e) {
            db.abort();
            _dbp.log().error("SignsSync", "Exception: "+e.getMessage());
            return;
        }
        finally { db.close();}  
    }
     
     
     
    private void _del(String ident, String userid, String tag) 
        throws DBSession.SessionError
    {
        MyDBSession db = _dbp.getDB();
        try {
            db.unlinkJsObject(ident, userid, userid);            
            _psub.put("object", tag, userid);
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
