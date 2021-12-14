
package no.polaric.aprsdb.http;
import no.polaric.aprsdb.*;
import no.polaric.aprsd.*;
import no.polaric.aprsd.http.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import uk.me.jstott.jcoord.*; 
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
 * This will eventually replace the XML service for trail and point cloud. 
 * see XMLserver.java
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
    //    _psub.createRoom("trackers:"+uid, Message.class); 
    }
        
        
    /** 
     * Return an error status message to client 
     */
    public String ERROR(Response resp, int status, String msg)
      { resp.status(status); return msg; }
      
      
      
    public String ABORT(Response resp, MyDBSession db, String logmsg, int status, String msg) {
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
         * Get "my trackers" for the logged in user. 
         ****************************************************************************/
         
        get("/trackers", "application/json", (req, resp) -> {
            DbList<Tracker> tr = null; 
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");
                
            /* Database transaction */
            MyDBSession db = _dbp.getDB();
            try {
                tr =  db.getTrackers(auth.userid);
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
                return ERROR(resp, 500, "Cannot parse input");   
                
            if (tr.user.equals(""))
                tr.user=null;
            if ( tr.user != null && !_api.getWebserver().getUserDb().hasUser(tr.user) )
                return ERROR(resp, 400, "User '"+tr.user+"' not found");
                
            /* Database transaction */
            MyDBSession db = _dbp.getDB();
            try {
                call = call.toUpperCase();
                Tracker dbtr = db.getTracker(call);
            
                /* If we own the tracker, we can update it */
                if (auth.userid.equals(dbtr.info.user)) 
                    db.updateTracker(call, tr.user, tr.alias, tr.icon);
                else {
                    return ABORT(resp, db, "POST /trackers/*: Item is owned by another user",
                        403, "Item is owned by another user");
                }
                /* 
                 * If ownership is transferred, notify receiver. 
                 * FIXME: Handle transfer to/from incident
                 */ 
                if (tr.user != null && !tr.user.equals(auth.userid)) {
                    _api.getWebserver().notifyUser(tr.user, 
                        new ServerAPI.Notification("system", "system", 
                           "Tracker '"+call+"' transferred from "+auth.userid, new Date(), 60));
                    _psub.put("trackers:"+tr.user, null);
                }
                _psub.put("trackers:"+auth.userid, null);
                    
                var pt = updateItem(call, tr.alias, tr.icon, req);
                db.commit();
                return (pt==null ? "OK" : "OK-ACTIVE");
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "POST /trackers/*: SQL error:"+e.getMessage(),
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
                    return ERROR(resp, 500, "Cannot parse input");   
            /* 
             * Check if user is allowed to post this for a tracker. Note that this check 
             * is only for active trackers. Non-active trackers will be allowed.
             * FIXME: Need to improve this check? 
             */
            var item = _api.getDB().getItem(tr.id, null); 
            if (item != null && !sarAuthForItem(req, item))
                return ERROR(resp, 403, "Not allowed to manage this tracker: "+tr.id);
            
            /* Database transaction */
            MyDBSession db = _dbp.getDB();
            try {
                tr.id = tr.id.toUpperCase();
                Tracker dbtr = db.getTracker(tr.id);
                
                if (dbtr == null)                     
                    db.addTracker(tr.id, auth.userid, tr.alias, tr.icon);
                else {
                    return ABORT(resp, db, "POST /trackers: Item is managed already",
                        403, "Item is managed already (by "+dbtr.info.user+")");
                }
                var pt = updateItem(tr.id, tr.alias, tr.icon, req);
                 _psub.put("trackers:"+auth.userid, null);
                db.commit();
                return (pt==null ? "OK" : "OK-ACTIVE");
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "POST /trackers: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close(); }
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
            
            MyDBSession db = _dbp.getDB();
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
            
            /* Get user info */
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");
            
            MyDBSession db = _dbp.getDB();
            try {
                call = call.toUpperCase();
                Tracker dbtr = db.getTracker(call);
                if (dbtr == null)
                    return ABORT(resp, db, "DELETE /trackers/*: Item not found: ",
                        404, "Item not found: "+call);
                        
                if (!auth.userid.equals(dbtr.info.user))
                    return ABORT(resp, db, "DELETE /trackers/*: Item is owned by another user",
                        403, "Item is owned by another user");
                 
                db.deleteTracker(call);
                updateItem(call, null, null, req);
                removeItem(call);
                 _psub.put("trackers:"+auth.userid, null);
                db.commit();
                return "OK";
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "DELETE /trackers/*: SQL error:"+e.getMessage(),
                    500, "Server error (SQL");
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
            MyDBSession db = _dbp.getDB();
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
            MyDBSession db = _dbp.getDB();
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
            pt.setTag("MANAGED");
            if ( pt.setAlias(alias) ) 
                notifyAlias(id, alias, req); 
            if ( pt.setIcon(icon) )
                notifyIcon(id, icon, req);
        }
        return pt; 
    }
    
    
    /* Remove item from managed set */    
    public void removeItem(String id) {
        TrackerPoint pt = _api.getDB().getItem(id, null, false);
        if (pt != null)
            pt.removeTag("MANAGED");
    }
    
   
    

}
