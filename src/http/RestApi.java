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
 
package no.polaric.aprsdb.http;
import no.arctic.core.*;
import no.arctic.core.httpd.*;
import no.arctic.core.auth.*;
import io.javalin.Javalin;
import io.javalin.http.Context;

import no.polaric.aprsdb.*;
import no.polaric.aprsd.*;
import no.polaric.aprsd.point.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import no.polaric.aprsd.filter.*;
import java.util.stream.Collectors;


/*
 * REST API.
 */
 
public class RestApi extends ServerBase implements JsonPoints
{
    private ServerConfig _api;
    private ServerConfig.PubSub _psub;
    private PluginApi _dbp;
    private String    _myCall;
    public java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
       
    public RestApi(ServerConfig api) {
        super (api); 
        _api = api;
        _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
        _myCall = api.getProperty("default.mycall", "NOCALL").toUpperCase();
    }
        
        
        
    /** 
     * Return an error status message to client 
     */
    public Object ERROR(Context ctx, int status, String msg)
      { ctx.status(status); ctx.result(msg); return null;}
    
      
      
    public Object ABORT(Context ctx, MyDBSession db, String logmsg, int status, String msg) {
        _dbp.log().warn("RestApi", logmsg);
        if (db!=null)
            db.abort();
        return ERROR(ctx, status, msg);
    }
      
    
    
    protected boolean sarAuthForItem(Context ctx, PointObject x) {
        AuthInfo auth = getAuthInfo(ctx);
        return x.hasTag(auth.tagsAuth) || auth.admin;
    }
    
    
    
    /** 
     * Set up the webservices. 
     */
    public void start() {   
        protect("/objects/*");
        /*
         * FIXME: With new auth scheme, we cannot have authentication and at the 
         * same time allow non-authenticated users to access a URL. We may need a special version of 
         * get-operations to read objects to allow non-authenticated users to read objects
         * that are open for all. 
         */
                
        _psub = _api.getWebserver().pubSub();
        _psub.createRoom("sharing", (Class) null); 
        _psub.createRoom("object", String.class /* tag */); 
      
        /***************************************************************************** 
         * REST Service
         * Get a list of users (and readonly attribute) with which the given object 
         * is shared.  
         *****************************************************************************/
         
        a.get("/objects/{tag}/{id}/share", (ctx) -> {
            var tag = ctx.pathParam("tag");
            var ident = ctx.pathParam("id");
           
           /* Database transaction */
            MyDBSession db = _dbp.getDB();
            try {
                var tr =  db.getJsUsers(ident);
                List<JsObject.User> usr = tr.toList();
                db.commit();
                ctx.json(usr);
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "GET /objects/*/*/share: SQL error:"+e.getMessage(), 500, "Server error (SQL)");
            }
            catch (java.lang.NumberFormatException e) {
                ABORT(ctx, db, "GET /objects/*/{id}/share: Object id must be numeric", 400, "Object id must be numeric");
            }
            finally { 
                db.close(); 
            }
        });
        
        
                
        /***************************************************************************** 
         * REST Service
         * Add a user or group that share this object.  
         *****************************************************************************/
         
        a.post("/objects/{tag}/{id}/share", (ctx) -> {         
            var tag = ctx.pathParam("tag");
            var ident = ctx.pathParam("id");
            var auth = getAuthInfo(ctx); 

            /* Get user info from request */
            var u = (JsObject.User) 
                ServerBase.fromJson(ctx.body(), JsObject.User.class);
        
            if (u.userid.matches("[@#].+") && !auth.operator && !auth.admin) {
                ERROR(ctx, 401, "You are not authorized to share with groups or #ALL");
                return; 
            }
            /* Database transaction */
            MyDBSession db = _dbp.getDB();
            try {                 
                if (u==null) {
                    ABORT(ctx, db, "POST /objects/*/*/share: cannot parse input", 500, "Cannot parse input");     
                    return;
                }
                if (ident.equals("_ALL_")) 
                    db.shareJsObjects(tag, auth.userid, u.userid, u.readOnly);
                else {
                    db.shareJsObject(ident, auth.userid,  u.userid, u.readOnly);
                
                    /* Notify receiving user */
                    if (!u.userid.matches("(#ALL)|(@.+)")) {
                        _psub.put("sharing", null, u.userid);
                        _api.getWebserver().notifyUser(u.userid, 
                            new ServerConfig.Notification("system", "share", 
                                auth.userid+" shared '"+tag+"' object with you" , new Date(), 4));
                    }
                }
                db.commit();
                if (ident.equals("_ALL_"))
                    ident += "@" + tag;
                _dbp.getSync().localUpdate("objshare", ident, auth.userid, "ADD", ctx.body());
                ctx.result("Ok");
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "POST /objects/*/*/share: SQL error:"+e.getMessage(), 500, "Server error (SQL)");
            }        
            catch (java.lang.NumberFormatException e) {
                ABORT(ctx, db, "POST /objects/*/{id}/share: Object id must be numeric", 400, "Object id must be numeric");
            }
            finally { 
                db.close(); 
            }   
        });
                
                
                
        /***************************************************************************** 
         * REST Service
         * Remove a user that share this object.  
         * return number of actual objects removed from database. 
         *****************************************************************************/
         
        a.delete("/objects/{tag}/{id}/share/{uid}", (ctx) -> {         
            var tag = ctx.pathParam("tag");
            var id = ctx.pathParam("id");
            var uid = ctx.pathParam("uid");
            var auth = getAuthInfo(ctx); 
                        
            /* Database transaction */
            MyDBSession db = _dbp.getDB();
            try {  
                int n = 0;
                if (id.equals("_ALL_"))
                    n = db.unlinkJsObjects(tag, auth.userid, uid);
                else {
                    n = db.unlinkJsObject(id, auth.userid, uid);
                    /* Notify receiving user */
                    _psub.put("sharing", null, uid);
                }
                db.commit();    
                if (id.equals("_ALL_"))
                    id += "@" + tag;
                _dbp.getSync().localUpdate("objshare", id, auth.userid, "DEL", uid);
                ctx.result(""+n);
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "POST /objects/*/*/share: SQL error:"+e.getMessage(), 500, "Server error (SQL)");
            }        
            catch (java.lang.NumberFormatException e) {
                ABORT(ctx, db, "POST /objects/*/{id}/share: Object id must be numeric", 400, "Object id must be numeric");
            }
            finally { 
                db.close(); 
            }   
        });
        
        
        /***************************************************************************
         * REST Service: 
         * Update an object for the logged in user.
         * FIXME: Sanitize input? 
         ***************************************************************************/
         
        a.put("/objects/{tag}/{id}", (ctx) -> {
            var tag = ctx.pathParam("tag");
            var id = ctx.pathParam("id");
            
            /* Note: this is JSON but we do NOT deserialize it. We store it. */
            String data = ctx.body();   
                
            /* Get user info */
            var auth = getAuthInfo(ctx); 
            if (auth == null) {
                ERROR(ctx, 500, "No authorization info found");
                return;
            }
            if (!auth.login()) {
                ERROR(ctx, 401, "Authentication required");
                return;
            }
            MyDBSession db = _dbp.getDB();
            try {
                db.updateJsObject(id, data);
                _psub.put("object", tag, auth.userid);
                db.commit();
                _dbp.getSync().localUpdate("object", tag+":"+id, auth.userid, "UPD", data);
                ctx.result("Ok");
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "PUT /objects/"+tag+"/"+id+": SQL error:"+e.getMessage(),
                    500, "Server error (SQL)");
            }
            finally { db.close(); }
            
        });
        
        
        
        /***************************************************************************
         * REST Service: 
         * Delete an object for the logged in user.
         * Return number of objects actually deleted from database. 
         ***************************************************************************/
         
        a.delete("/objects/{tag}/{id}", (ctx) -> {
            var tag = ctx.pathParam("tag");
            var id = ctx.pathParam("id");
            
            /* Get user info */
            var auth = getAuthInfo(ctx); 
            if (auth == null) {
                ERROR(ctx, 500, "No authorization info found");
                return;
            }
            if (!auth.login()) {
                ERROR(ctx, 401, "Authentication required");
                return; 
            }
            MyDBSession db = _dbp.getDB();
            try {
                int n = db.unlinkJsObject(id, auth.userid, auth.userid);              
                _psub.put("object", tag, auth.userid);
                db.commit();
                _dbp.getSync().localUpdate("object", tag+":"+id, auth.userid, "DEL", null);
                ctx.result(""+n);
            }
            catch (java.lang.NumberFormatException e) {
                ABORT(ctx, db, "DELETE /objects/"+tag+"/"
                    +id+": Expected numeric object identifier", 
                    400, "Expected numeric object identifier");
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "DELETE /users/"+tag+"/"+id+": SQL error:"+e.getMessage(),
                    500, "Server error (SQL)");
            }
            finally { db.close();}
        } );
                
        
        /***************************************************************************** 
         * REST Service
         * Get a single (raw text) object. 
         *****************************************************************************/
         
        a.get("/objects/{tag}/{id}", (ctx) -> {
            var tag = ctx.pathParam("tag");
            var id = ctx.pathParam("id");
            
            /* Get user info */
            var auth = getAuthInfo(ctx); 
            if (auth == null) {
                ERROR(ctx, 500, "No authorization info found");
                return;
            }
            MyDBSession db = _dbp.getDB();
            try {
                String aa =  db.getJsObject(auth.userid, auth.groupid, tag, id);            
                if (aa == null) {
                    ABORT(ctx, db, "GET /objects/"+tag+"/"+id+": Item not found: ",
                        404, "Item not found: "+tag+": "+id);
                    return;
                }
                db.commit();
                ctx.json(aa);
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "GET /objects/"+tag+"/"+id+": SQL error:"+e.getMessage(),
                    500, "Server error (SQL)");
            }      
            catch (java.lang.NumberFormatException e) {
                ABORT(ctx, db, "GET /objects/*/*: Object id must be numeric", 400, "Object id must be numeric");
            }
            finally { 
                db.close(); 
            }
        } );
        
        
        /* Open version */
        a.get("/open/objects/{tag}/{id}", (ctx) -> {
            var tag = ctx.pathParam("tag");
            var id = ctx.pathParam("id");
            
            MyDBSession db = _dbp.getDB();
            try {
                String a =  db.getJsObject(null, null, tag, id);            
                if (a == null)
                    ABORT(ctx, db, "GET /open/objects/"+tag+"/"+id+": Item not found: ",
                        404, "Item not found: "+tag+": "+id);
                db.commit();
                ctx.json(a);
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "GET /open/objects/"+tag+"/"+id+": SQL error:"+e.getMessage(),
                    500, "Server error (SQL)");
            }      
            catch (java.lang.NumberFormatException e) {
                ABORT(ctx, db, "GET /open/objects/*/*: Object id must be numeric", 400, "Object id must be numeric");
            }
            finally { 
                db.close(); 
            }

        } );
        
                
        /***************************************************************************** 
         * REST Service
         * Get a list of (JSON) objects for the logged in user. 
         *****************************************************************************/
         
        a.get("/objects/{tag}", (ctx) -> {
            var tag = ctx.pathParam("tag");
            
            /* Get user info */
            var auth = getAuthInfo(ctx); 
            MyDBSession db = _dbp.getDB();
            try {
                DbList<JsObject> aa = null; 
                aa =  db.getJsObjects(auth.userid, auth.groupid, tag);
                List<JsObject> res = aa.toList().stream().collect(Collectors.toList());
                db.commit();
                ctx.json(res);
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "GET /objects/"+tag+": SQL error:"+e.getMessage(),
                    500, "Server error (SQL)");
            }
            catch (Exception e) {
                e.printStackTrace(System.out);
                ABORT(ctx, db, "GET /objects/"+tag+": Error:"+e.getMessage(),
                    500, "Server error");
            }
            finally { 
                db.close(); 
            }

        });
            
            
        /* Open version */    
        a.get("/open/objects/{tag}", (ctx) -> {
            var tag = ctx.pathParam("tag");
            
            MyDBSession db = _dbp.getDB();
            try {
                DbList<JsObject> aa = null; 
                aa =  db.getJsObjects(null, null, tag);
                List<JsObject> res = aa.toList().stream().collect(Collectors.toList());
                db.commit();
                ctx.json(res);
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "GET /objects/"+tag+": SQL error:"+e.getMessage(),
                    500, "Server error (SQL)");
            }
            catch (Exception e) {
                e.printStackTrace(System.out);
                ABORT(ctx, db, "GET /objects/"+tag+": Error:"+e.getMessage(),
                    500, "Server error");
            }
            finally { 
                db.close(); 
            }

        });
                
                
        /************************************************************************** 
         * REST Service: 
         * [id]. 
         * We assume that this is a JSON object but do not parse it. 
         **************************************************************************/
         
        a.post("/objects/{tag}", (ctx) -> {
            var tag = ctx.pathParam("tag");
            
            /* Get user info */
            var auth = getAuthInfo(ctx); 
            if (auth == null) {
                ERROR(ctx, 500, "No authorization info found");
                return;
            }
            if (!auth.login()) {
                ERROR(ctx, 401, "Authentication required");
                return;
            }
            /* Note: this is JSON but we do NOT deserialize it. We store it. */
            String data = ctx.body(); 
            
            MyDBSession db = _dbp.getDB();
            try {
                String id = db.addJsObject(_myCall, auth.userid, tag, data);
                _psub.put("object", tag, auth.userid);
                db.commit();
                _dbp.getSync().localUpdate("object", tag+":"+id, auth.userid, "ADD", data);
                ctx.result(id);
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "POST /objects/"+tag+": SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close(); }
        });
    
    }
 

}
