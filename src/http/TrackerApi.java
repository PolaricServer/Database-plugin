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
 * REST API for trackers
 */
 
public class TrackerApi extends ServerBase implements JsonPoints
{
    private AprsServerConfig _api;
    private PubSub _psub;
    private PluginApi _dbp;
    public java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
       
    public TrackerApi(AprsServerConfig api) {
        super (api); 
        _api = api;
        _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
        _psub = (PubSub) _api.getWebserver().pubSub();
    }
        

        
    /** 
     * Return an error status message to client 
     */
    public Object ERROR(Context ctx, int status, String msg)
      { ctx.status(status); ctx.result(msg); return null;}
    
      
      
    public Object ABORT(Context ctx, TrackerDBSession db, String logmsg, int status, String msg) {
        _dbp.log().warn("TrackerApi", logmsg);
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

        protect("/trackers");
        protect("/trackers/*");
        
        
        /**************************************************************************** 
         * REST Service
         * Get "my trackers" for the logged in user or for the user given as request
         * parameter 'user'.
         ****************************************************************************/
         
        a.get("/trackers", (ctx) -> {
            DbList<Tracker> tr = null; 
            var userid = ctx.queryParam("user");
            var auth = getAuthInfo(ctx); 
            if (userid==null || userid=="") {
                if (auth == null) {
                    ERROR(ctx, 500, "No authorization info found");
                    return;
                }
                userid = auth.userid;
            }    
            
            /* Database transaction */
            TrackerDBSession db = new TrackerDBSession(_dbp.getDB());
            try {
                tr = db.getTrackers(userid);
                List<Tracker.Info> tri = tr.toList().stream().map(x -> x.info).collect(Collectors.toList());
                _psub.createRoom("trackers:"+auth.userid, (Class) null);
                db.commit();
                ctx.json(tri);
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "GET /users/*/trackers: SQL error:"+e.getMessage(), 500, "Server error (SQL)");
            }
            finally { 
                db.close(); 
            }
        });
        
        
        
        /**************************************************************************** 
         * REST Service: 
         * Update a tracker if it exists in the database and is owned by the user. 
         ****************************************************************************/
        
        a.put("/trackers/{call}", (ctx) -> {
            var call = ctx.pathParam("call"); 
            
            /* Get user info */
            var auth = getAuthInfo(ctx); 
            if (auth == null) {
                ERROR(ctx, 500, "No authorization info found");
                return;
            }
            
            /* Get tracker info from request */
            Tracker.Info tr = (Tracker.Info) 
                ServerBase.fromJson(ctx.body(), Tracker.Info.class);
            if (tr==null) { 
                ERROR(ctx, 400, "Cannot parse input");   
                return;
            }
            if (tr.user.equals(""))
                tr.user=null;
            if ( tr.user != null && !((WebServer)_api.getWebserver()).userDb().hasUser(tr.user) ) {
                ERROR(ctx, 400, "User '"+tr.user+"' not found");
                return;
            }
            
            /* Database transaction */
            TrackerDBSession db = new TrackerDBSession(_dbp.getDB());
            try {
                call = call.toUpperCase();
                Tracker dbtr = db.getTracker(call);
            
                /* If we own the tracker, we can update it */
                if (auth.admin || auth.userid.equals(dbtr.info.user)) 
                    db.updateTracker(call, tr.user, tr.alias, tr.icon);
                else {
                    ABORT(ctx, db, "PUT /trackers/*: Item not owned by the user",
                        403, "Item must be owned by you to allow update (or you must be admin)");
                    return;
                }
                
                /* 
                 * If ownership is transferred, notify receiver and previous user . 
                 * FIXME: Handle transfer to/from incident
                 */ 
                if (tr.user != null && !tr.user.equals(auth.userid)) {
                    /* notify receiver */
                    _api.getWebserver().notifyUser(tr.user, 
                        new ServerConfig.Notification("system", "system", 
                           "Tracker '"+call+"' transferred TO you from "+auth.userid, new Date(), 60));
                    
                    /* notify previous user */
                    _api.getWebserver().notifyUser(auth.userid, 
                        new ServerConfig.Notification("system", "system", 
                           "Tracker '"+call+"' transferred FROM you to "+tr.user, new Date(), 60));
                    
                    _psub.put("trackers:"+tr.user, null);
                }
                _psub.put("trackers:"+auth.userid, null);
                    
                var pt = updateItem(call, tr.alias, tr.icon, ctx);
                db.commit();
                ctx.result(pt==null ? "OK" : "OK-ACTIVE");
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "PUT /trackers/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close(); }
        });
        
        
        
        /**************************************************************************** 
         * REST Service: 
         * Save a tracker for the logged in user.
         ****************************************************************************/
         
        a.post("/trackers", (ctx) -> {
            /* Get user info */
            var auth = getAuthInfo(ctx); 
            if (auth == null) {
                ERROR(ctx, 500, "No authorization info found");
                return;
            }
            
            /* Get tracker info from request */
            Tracker.Info tr = (Tracker.Info) 
                ServerBase.fromJson(ctx.body(), Tracker.Info.class);
            if (tr==null) { 
                ERROR(ctx, 400, "Cannot parse input");   
                return;
            }
            
            /* 
             * Check if user is allowed to post this for a tracker. Note that this check 
             * is only for active trackers. Non-active trackers will be allowed.
             * FIXME: Need to improve this check? 
             */
            StationDB db = _api.getDB();
            var item = db.getItem(tr.id, null); 
            if (item != null && !sarAuthForItem(ctx, item)) {
                ERROR(ctx, 403, "Not allowed to manage this tracker: "+tr.id);
                return;
            }
            if (item != null && item.hasTag("RMAN")) {
                ERROR(ctx, 403, "Item is managed already (on another server)");
                return;
            }
            
            /* Database transaction */
            TrackerDBSession tdb = new TrackerDBSession(_dbp.getDB());
            try {
                tr.id = tr.id.toUpperCase();
                Tracker dbtr = tdb.getTracker(tr.id);
                
                if (dbtr == null)                     
                    tdb.addTracker(tr.id, auth.userid, tr.alias, tr.icon);
                else {
                    ABORT(ctx, tdb, "POST /trackers: Item is managed already",
                        403, "Item is managed already (by "+dbtr.info.user+")");
                    return;
                }
                var pt = updateItem(tr.id, tr.alias, tr.icon, ctx);
                 _psub.put("trackers:"+auth.userid, null);
                tdb.commit();
                ctx.result(pt==null ? "OK" : "OK-ACTIVE");
            }
            
            catch (java.sql.SQLException e) {
                ABORT(ctx, tdb, "POST /trackers: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            catch (Exception e) {
                e.printStackTrace(System.out);
                ABORT(ctx, tdb, "POST /trackers: rror:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { tdb.close(); }
        } );
        
                
        
        /**************************************************************************
         * REST Service: 
         * Delete a tag for the logged in user. 
         **************************************************************************/
         
        a.delete("/trackers/tags/{tag}", (ctx) -> {
            var tag = ctx.pathParam("tag"); 
            /* Get user info */
            var auth = getAuthInfo(ctx); 
            if (auth == null) {
                ERROR(ctx, 500, "No authorization info found");
                return;
            }
            TrackerDBSession db = new TrackerDBSession(_dbp.getDB());
            try {
                db.deleteTrackerTag(auth.userid, tag);
                removeActiveTag(db.getTrackers(auth.userid).toList(), tag);
                db.commit();
                ctx.result("OK");
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "DELETE /trackers/tags/*: SQL error:"+e.getMessage(),
                    500, "Server error (SQL");
            }
            finally { db.close();}  
        } );
        
        
        
        /**************************************************************************
         * REST Service: 
         * Delete a tracker for the logged in user. 
         **************************************************************************/
         
        a.delete("/trackers/{call}", (ctx) -> {
            var call = ctx.pathParam("call");
            var forcep = ctx.queryParam("force");
            var force = ((forcep!=null && forcep.matches("true|TRUE")) ? true : false);
             
            /* Get user info */
            var auth = getAuthInfo(ctx); 
            if (auth == null) {
                ERROR(ctx, 500, "No authorization info found");
                return;
            }
            TrackerDBSession db = new TrackerDBSession(_dbp.getDB());
            try {
                call = call.toUpperCase();
                Tracker dbtr = db.getTracker(call);
                if (dbtr == null && !force) {
                    ABORT(ctx, db, "DELETE /trackers/*: Item not found: ",
                        404, "Item not found (on this server): "+call);
                    return;
                }
                
                /* Do we own the tracker? */
                if (dbtr!=null && !auth.userid.equals(dbtr.info.user) && !force) {
                    ABORT(ctx, db, "DELETE /trackers/*: Item is owned by another user",
                        403, "Item is owned by another user");
                    return;
                }
                if (dbtr != null)
                    db.deleteTracker(call);
                updateItem(call, null, null, ctx);
                removeItem(call, ctx);
                
                if (force && dbtr!=null)
                    _api.getWebserver().notifyUser(dbtr.info.user, 
                        new ServerConfig.Notification("system", "system", "Your Tracker '"+call+"' was removed by "+auth.userid, new Date(), 60));
                        
                _psub.put("trackers:"+(dbtr==null ? auth.userid : dbtr.info.user), null);
                db.commit();
                ctx.result("OK");
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "DELETE /trackers/*: SQL error:"+e.getMessage(),
                    500, "Server error (SQL)");
            }           
            catch (java.lang.Exception e) {
                e.printStackTrace(System.out);
                ABORT(ctx, db, "DELETE /trackers/*: Server error:"+e.getMessage(),
                    500, "Server error");
            }
            finally { db.close();}  
        } );
        
        
        
        /**************************************************************************** 
         * REST Service
         * Get tracker tags for the logged in user 
         ****************************************************************************/
         
        a.get("/trackers/tags", (ctx) -> {
            /* Get user info */
            var auth = getAuthInfo(ctx); 
            if (auth == null) {
                ERROR(ctx, 500, "No authorization info found");
                return;
            }
            /* Database transaction */
            TrackerDBSession db = new TrackerDBSession(_dbp.getDB());
            try {
                DbList<String> tags = db.getTrackerTagsUser(auth.userid);
                db.commit();
                ctx.json(tags.toList());
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "GET /trackers/*/tags: SQL error:"+e.getMessage(), 500, "Server error (SQL)");
            }
            finally { 
                db.close(); 
            }

        } );
        
        
        
        /**************************************************************************** 
         * REST Service: 
         * Add a tag to the logged in user's trackers
         ****************************************************************************/
         
        a.post("/trackers/tags", (ctx) -> {
            /* Get user info */
            var auth = getAuthInfo(ctx); 
            if (auth == null) {
                ERROR(ctx, 500, "No authorization info found");
                return;
            }
            
            /* Database transaction */
            TrackerDBSession db = new TrackerDBSession(_dbp.getDB());
            try { 
                DbList<Tracker> tr =  db.getTrackers(auth.userid);
                String[] a = (String[]) ServerBase.fromJson(ctx.body(), String[].class);
                for (String x : a) {
                    db.addTrackerTag(auth.userid, x);
                    updateActiveTag(tr.toList(), x);
                }
                db.commit();
                ctx.result("OK");
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "POST /trackers: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close(); }
        } );
    
    }


    /* Remove tag on active items */
    private void removeActiveTag(List<Tracker> tr, String tag) {
        for (Tracker x : tr)
            if (x.getStation() != null)
                x.getStation().removeTag(tag);
    }
    
    
    /* Set tag on active items */
    private void updateActiveTag(List<Tracker> tr, String tag) {
        for (Tracker x : tr)
            if (x.getStation() != null)
                x.getStation().setTag(tag);
    }
    
    

    /* Set alias and/or icon on specific item (if active) */
    public TrackerPoint updateItem(String id, String alias, String icon, Context ctx) {
        TrackerPoint pt = _api.getDB().getItem(id, null, false);
        if (pt != null) {
            boolean mgd = pt.hasTag("MANAGED"); 
            boolean setItem = (alias!=null || icon!=null); 
            boolean changed = pt.setAlias(alias);
            changed |= pt.setIcon(icon);
            pt.setTag("MANAGED");
            
            /* Send RMAN message to other servers but only if alias or icon was 
             * set when adding item to myTrackers OR
             * changed on already managed item
             */
            if ((mgd && changed) || (!mgd && setItem)) {
                pt.setTag("_srman");
                if (_api.getRemoteCtl() != null) 
                    _api.getRemoteCtl().sendRequestAll("RMAN", pt.getIdent()+" "+alias+" "+icon, null);
            }
                
        }
        return pt; 
    }
    
    
    /* Remove item from managed set */    
    public void removeItem(String id, Context ctx) {
        TrackerPoint pt = _api.getDB().getItem(id, null, false);
        
        /* pt is null if tracker is not active */
        if (pt == null) 
            return;
            
        pt.removeTag("MANAGED");
        pt.removeTag("RMAN"); 
        pt.removeTag("_srman");
        
        if (_api.getRemoteCtl() != null) 
            _api.getRemoteCtl().sendRequestAll("RMRMAN", pt.getIdent(), null);
            
    }
    
   
    

}
