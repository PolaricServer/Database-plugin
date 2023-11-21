
package no.polaric.aprsdb.http;
import no.polaric.aprsdb.*;
import no.polaric.aprsd.*;
import no.polaric.aprsd.http.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import no.polaric.aprsd.filter.*;
import spark.Request;
import spark.Response;
import spark.route.Routes;
import static spark.Spark.get;
import static spark.Spark.put;
import static spark.Spark.*;
import spark.QueryParamsMap;
import java.util.stream.Collectors;
import javax.servlet.*;
import javax.servlet.http.*;
import org.eclipse.jetty.server.*;


/*
 * REST API for trackers
 */
 
public class TrackerApi extends ServerBase implements JsonPoints
{
    private ServerAPI _api;
    private PubSub _psub;
    private PluginApi _dbp;
    public java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
       
    public TrackerApi(ServerAPI api) {
        super (api); 
        _api = api;
        _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
        _psub = (no.polaric.aprsd.http.PubSub) _api.getWebserver().getPubSub();
    }
        
        
    /** 
     * Return an error status message to client 
     */
    public String ERROR(Response resp, int status, String msg)
      { resp.status(status); return msg; }
      
      
      
    public String ABORT(Response resp, TrackerDBSession db, String logmsg, int status, String msg) {
        _dbp.log().warn("TrackerApi", logmsg);
        db.abort();
        return ERROR(resp, status, msg);
    }
      
    
    
    protected boolean sarAuthForItem(Request req, PointObject x) {
        return (getAuthInfo(req).itemSarAuth(x));
    }
    
    /** 
     * Set up the webservices. 
     */
    public void start() {   
        _api.getWebserver().corsEnable("/trackers");
        _api.getWebserver().corsEnable("/trackers/*");
        _api.getWebserver().protectUrl("/trackers");
        _api.getWebserver().protectUrl("/trackers/*");
        
        
        /**************************************************************************** 
         * REST Service
         * Get "my trackers" for the logged in user or for the user given as request
         * parameter 'user'.
         ****************************************************************************/
         
        get("/trackers", "application/json", (req, resp) -> {
            DbList<Tracker> tr = null; 
            var userid = req.queryParams("user");
            var auth = getAuthInfo(req); 
            if (userid==null || userid=="") {
                if (auth == null)
                    return ERROR(resp, 500, "No authorization info found");
                userid = auth.userid;
            }    
            
            /* Database transaction */
            TrackerDBSession db = new TrackerDBSession(_dbp.getDB());
            try {
                tr =  db.getTrackers(userid);
                List<Tracker.Info> tri = tr.toList().stream().map(x -> x.info).collect(Collectors.toList());
                _psub.createRoom("trackers:"+auth.userid, (Class) null);
                db.commit();
                return tri;
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "GET /users/*/trackers: SQL error:"+e.getMessage(), 500, "Server error (SQL)");
            }
            finally { 
                db.close(); 
            }

        }, ServerBase::toJson );
        
        
        
        /**************************************************************************** 
         * REST Service: 
         * Update a tracker if it exists in the database and is owned by the user. 
         ****************************************************************************/
        
        put("/trackers/*",  (req, resp) -> {
            String call = req.splat()[0];
            
            /* Get user info */
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");
            
            /* Get tracker info from request */
            Tracker.Info tr = (Tracker.Info) 
                ServerBase.fromJson(req.body(), Tracker.Info.class);
            if (tr==null) 
                return ERROR(resp, 400, "Cannot parse input");   
                
            if (tr.user.equals(""))
                tr.user=null;
            if ( tr.user != null && !_api.getWebserver().getUserDb().hasUser(tr.user) )
                return ERROR(resp, 400, "User '"+tr.user+"' not found");
                
            /* Database transaction */
            TrackerDBSession db = new TrackerDBSession(_dbp.getDB());
            try {
                call = call.toUpperCase();
                Tracker dbtr = db.getTracker(call);
            
                /* If we own the tracker, we can update it */
                if (auth.admin || auth.userid.equals(dbtr.info.user)) 
                    db.updateTracker(call, tr.user, tr.alias, tr.icon);
                else {
                    return ABORT(resp, db, "PUT /trackers/*: Item not owned by the user",
                        403, "Item must be owned by you to allow update (or you must be admin)");
                }
                
                /* 
                 * If ownership is transferred, notify receiver and previous user . 
                 * FIXME: Handle transfer to/from incident
                 */ 
                if (tr.user != null && !tr.user.equals(auth.userid)) {
                    /* notify receiver */
                    _api.getWebserver().notifyUser(tr.user, 
                        new ServerAPI.Notification("system", "system", 
                           "Tracker '"+call+"' transferred TO you from "+auth.userid, new Date(), 60));
                    
                    /* notify previous user */
                    _api.getWebserver().notifyUser(auth.userid, 
                        new ServerAPI.Notification("system", "system", 
                           "Tracker '"+call+"' transferred FROM you to "+tr.user, new Date(), 60));
                    
                    _psub.put("trackers:"+tr.user, null);
                }
                _psub.put("trackers:"+auth.userid, null);
                    
                var pt = updateItem(call, tr.alias, tr.icon, req);
                db.commit();
                return (pt==null ? "OK" : "OK-ACTIVE");
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "PUT /trackers/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close(); }
        });
        
        
        /**************************************************************************** 
         * REST Service: 
         * Save a tracker for the logged in user.
         ****************************************************************************/
         
        post("/trackers", (req, resp) -> {
            /* Get user info */
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");
            
            /* Get tracker info from request */
            Tracker.Info tr = (Tracker.Info) 
                ServerBase.fromJson(req.body(), Tracker.Info.class);
            if (tr==null) 
                return ERROR(resp, 400, "Cannot parse input");   
                
            /* 
             * Check if user is allowed to post this for a tracker. Note that this check 
             * is only for active trackers. Non-active trackers will be allowed.
             * FIXME: Need to improve this check? 
             */
            StationDB db = _api.getDB();
            var item = db.getItem(tr.id, null); 
            if (item != null && !sarAuthForItem(req, item))
                return ERROR(resp, 403, "Not allowed to manage this tracker: "+tr.id);
            if (item != null && item.hasTag("RMAN"))
                return ERROR(resp, 403, "Item is managed already (on another server)");
            
            /* Database transaction */
            TrackerDBSession tdb = new TrackerDBSession(_dbp.getDB());
            try {
                tr.id = tr.id.toUpperCase();
                Tracker dbtr = tdb.getTracker(tr.id);
                
                if (dbtr == null)                     
                    tdb.addTracker(tr.id, auth.userid, tr.alias, tr.icon);
                else {
                    return ABORT(resp, tdb, "POST /trackers: Item is managed already",
                        403, "Item is managed already (by "+dbtr.info.user+")");
                }
                var pt = updateItem(tr.id, tr.alias, tr.icon, req);
                 _psub.put("trackers:"+auth.userid, null);
                tdb.commit();
                return (pt==null ? "OK" : "OK-ACTIVE");
            }
            
            catch (java.sql.SQLException e) {
                return ABORT(resp, tdb, "POST /trackers: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            catch (Exception e) {
                e.printStackTrace(System.out);
                return ABORT(resp, tdb, "POST /trackers: rror:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { tdb.close(); }
        } );
        
                
        
        /**************************************************************************
         * REST Service: 
         * Delete a tag for the logged in user. 
         **************************************************************************/
         
        delete("/trackers/tags/*", (req, resp) -> {
            String tag = req.splat()[0];
            
            /* Get user info */
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");
            
            TrackerDBSession db = new TrackerDBSession(_dbp.getDB());
            try {
                db.deleteTrackerTag(auth.userid, tag);
                removeActiveTag(db.getTrackers(auth.userid).toList(), tag);
                db.commit();
                return "OK";
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "DELETE /trackers/tags/*: SQL error:"+e.getMessage(),
                    500, "Server error (SQL");
            }
            finally { db.close();}  
        } );
        
        
        
        /**************************************************************************
         * REST Service: 
         * Delete a tracker for the logged in user. 
         **************************************************************************/
         
        delete("/trackers/*", (req, resp) -> {
            String call = req.splat()[0];
            var forcep = req.queryParams("force");
            var force = ((forcep!=null && forcep.matches("true|TRUE")) ? true : false);
             
            /* Get user info */
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");
            
            TrackerDBSession db = new TrackerDBSession(_dbp.getDB());
            try {
                call = call.toUpperCase();
                Tracker dbtr = db.getTracker(call);
                if (dbtr == null && !force)
                    return ABORT(resp, db, "DELETE /trackers/*: Item not found: ",
                        404, "Item not found (on this server): "+call);
                        
                /* Do we own the tracker? */
                if (dbtr!=null && !auth.userid.equals(dbtr.info.user) && !force)
                    return ABORT(resp, db, "DELETE /trackers/*: Item is owned by another user",
                        403, "Item is owned by another user");
                 
                if (dbtr != null)
                    db.deleteTracker(call);
                updateItem(call, null, null, req);
                removeItem(call, req);
                
                if (force && dbtr!=null)
                    _api.getWebserver().notifyUser(dbtr.info.user, 
                        new ServerAPI.Notification("system", "system", "Your Tracker '"+call+"' was removed by "+auth.userid, new Date(), 60));
                        
                _psub.put("trackers:"+(dbtr==null ? auth.userid : dbtr.info.user), null);
                db.commit();
                return "OK";
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "DELETE /trackers/*: SQL error:"+e.getMessage(),
                    500, "Server error (SQL)");
            }           
            catch (java.lang.Exception e) {
                e.printStackTrace(System.out);
                return ABORT(resp, db, "DELETE /trackers/*: Server error:"+e.getMessage(),
                    500, "Server error");
            }
            finally { db.close();}  
        } );
        
        
        
        /**************************************************************************** 
         * REST Service
         * Get tracker tags for the logged in user 
         ****************************************************************************/
         
        get("/trackers/tags", "application/json", (req, resp) -> {
            /* Get user info */
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");
                
            /* Database transaction */
            TrackerDBSession db = new TrackerDBSession(_dbp.getDB());
            try {
                DbList<String> tags = db.getTrackerTagsUser(auth.userid);
                db.commit();
                return tags.toList();
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "GET /trackers/*/tags: SQL error:"+e.getMessage(), 500, "Server error (SQL)");
            }
            finally { 
                db.close(); 
            }

        }, ServerBase::toJson );
        
        
        
        /**************************************************************************** 
         * REST Service: 
         * Add a tag to the logged in user's trackers
         ****************************************************************************/
         
        post("/trackers/tags", (req, resp) -> {
            /* Get user info */
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");
                
            /* Database transaction */
            TrackerDBSession db = new TrackerDBSession(_dbp.getDB());
            try { 
                DbList<Tracker> tr =  db.getTrackers(auth.userid);
                String[] a = (String[]) ServerBase.fromJson(req.body(), String[].class);
                for (String x : a) {
                    db.addTrackerTag(auth.userid, x);
                    updateActiveTag(tr.toList(), x);
                }
                db.commit();
                return "OK";
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "POST /trackers: SQL error:"+e.getMessage(),
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
    public TrackerPoint updateItem(String id, String alias, String icon, Request req) {
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
    public void removeItem(String id, Request req) {
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
