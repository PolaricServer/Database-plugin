
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
 
public class RestApi extends ServerBase implements JsonPoints
{
    private ServerAPI _api;
    private PubSub _psub;
    private PluginApi _dbp;
    public java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
       
    public RestApi(ServerAPI api) {
        super (api); 
        _api = api;
        _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
    }
        
        
    /** 
     * Return an error status message to client 
     */
    public String ERROR(Response resp, int status, String msg)
      { resp.status(status); return msg; }
      
      
      
    public String ABORT(Response resp, MyDBSession db, String logmsg, int status, String msg) {
        _dbp.log().warn("RestApi", logmsg);
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
        _api.getWebserver().corsEnable("/objects/*");
        _api.getWebserver().protectUrl("/trackers");
        _api.getWebserver().protectUrl("/trackers/*");
        _api.getWebserver().protectUrl("/objects/*");
                
        _psub = (no.polaric.aprsd.http.PubSub) _api.getWebserver().getPubSub();
        _psub.createRoom("sharing", (Class) null); 
        _psub.createRoom("object", String.class /* tag */); 
        
        
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
                db.commit();
                return tri;
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "GET /users/*/trackers: SQL error:"+e.getMessage(), 500, null);
            }
            finally { 
                db.close(); 
            }

        }, ServerBase::toJson );
        
        
        
        
        /**************************************************************************** 
         * REST Service: 
         * Save a tracker for the logged in user. Update if it exists in the database 
         * and is owned by the user. 
         ****************************************************************************/
         
        post("/trackers", (req, resp) -> {
            MyDBSession db = _dbp.getDB();
            
            /* Get user info */
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");
            
            /* Get tracker info from request */
            Tracker.Info tr = (Tracker.Info) 
                ServerBase.fromJson(req.body(), Tracker.Info.class);
                
            /* 
             * Check if user is allowed to post this for a tracker. Note that this check 
             * is only for active trackers. Non-active trackers will be allowed.
             * FIXME: Need to improve this check? 
             */
            var item = _api.getDB().getItem(tr.id, null);
            if (item != null && sarAuthForItem(req, item))
                return ERROR(resp, 403, "Not allowed to use this tracker: "+tr.id);
            
            /* Database transaction */
            try {
                if (tr==null) 
                    return ABORT(resp, db, "POST /users/*/trackers: cannot parse input", 
                        500, "Cannot parse input");
                
                tr.id = tr.id.toUpperCase();
                Tracker dbtr = db.getTracker(tr.id);
                
                if (dbtr == null)                     
                    db.addTracker(tr.id, auth.userid, tr.alias, tr.icon);
   
                /* If we own the tracker, we can update it */
                else if (auth.userid.equals(dbtr.info.user)) 
                    db.updateTracker(tr.id, tr.alias, tr.icon);
                else {
                    return ABORT(resp, db, "POST /users/*/trackers: Item is owned by another user",
                        403, "Item is owned by another user");
                }
                var pt = updateItem(tr.id, tr.alias, tr.icon, req);
                db.commit();
                return (pt==null ? "OK" : "OK-ACTIVE");
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "POST /users/*/trackers: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close(); }
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
                        404, "Item not found"+call);
                        
                if (!auth.userid.equals(dbtr.info.user))
                    return ABORT(resp, db, "DELETE /trackers/*: Item is owned by another user",
                        403, "Item is owned by another user");
                 
                db.deleteTracker(call);
                updateItem(call, null, null, req);
                removeItem(call);
                db.commit();
                return "OK";
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "DELETE /trackers/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close();}  
        } );
        
                
        /***************************************************************************** 
         * REST Service
         * Get a list of users (and readonly attribute) with which the given object 
         * is shared.  
         *****************************************************************************/
         
        get("/objects/*/*/share", "application/json", (req, resp) -> {
            String tag = req.splat()[0];
            String id = req.splat()[1];
          
            /* Database transaction */
            MyDBSession db = _dbp.getDB();
            try {
                long oid = Long.parseLong(id);
                var tr =  db.getJsUsers(oid);
                List<JsObject.User> usr = tr.toList();
                db.commit();
                return usr;
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "GET /objects/*/*/share: SQL error:"+e.getMessage(), 500, null);
            }
            catch (java.lang.NumberFormatException e) {
                return ABORT(resp, db, "GET /objects/*/*/share: Object id must be numeric", 400, null);
            }
            finally { 
                db.close(); 
            }
        }, ServerBase::toJson );
        
        
                
        /***************************************************************************** 
         * REST Service
         * Add a user or group that share this object.  
         *****************************************************************************/
         
        post("/objects/*/*/share", (req, resp) -> {         
            String tag = req.splat()[0];
            String id = req.splat()[1];
            var auth = getAuthInfo(req); 
            
            /* Get user info from request */
            var u = (JsObject.User) 
                ServerBase.fromJson(req.body(), JsObject.User.class);
        
            if (u.userid.matches("[@#].+") && !auth.sar && !auth.admin)
                return ERROR(resp, 402, "You are not authorized to share with groups or #ALL");
        
            /* Database transaction */
            MyDBSession db = _dbp.getDB();
            try {                 
                if (u==null) 
                    return ABORT(resp, db, "POST /objects/*/*/share: cannot parse input", 
                        500, "Cannot parse input");     
                        
                if (id.equals("_ALL_")) 
                    db.shareJsObjects(tag, auth.userid, u.userid, u.readOnly);
                else {
                    long oid = Long.parseLong(id);
                    db.shareJsObject(oid, auth.userid,  u.userid, u.readOnly);
                
                    /* Notify receiving user */
                    if (!u.userid.matches("(#ALL)|(@.+)")) {
                        _psub.put("sharing", null, u.userid);
                        _api.getWebserver().notifyUser(u.userid, 
                            new ServerAPI.Notification("system", "share", 
                                auth.userid+" shared '"+tag+"' object with you" , new Date(), 4));
                    }
                }
                db.commit();
                return "Ok";
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "POST /objects/*/*/share: SQL error:"+e.getMessage(), 500, null);
            }        
            catch (java.lang.NumberFormatException e) {
                return ABORT(resp, db, "POST /objects/*/*/share: Object id must be numeric", 400, null);
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
         
        delete("/objects/*/*/share/*", (req, resp) -> {         
            String tag = req.splat()[0];
            String id = req.splat()[1];
            String uid = req.splat()[2];
            var auth = getAuthInfo(req); 
                        
            /* Database transaction */
            MyDBSession db = _dbp.getDB();
            try {  
                int n = 0;
                if (id.equals("_ALL_"))
                    n = db.unlinkJsObjects(tag, auth.userid, uid);
                else {
                    long oid = Long.parseLong(id);
                    n = db.unlinkJsObject(oid, auth.userid, uid);
                    /* Notify receiving user */
                    _psub.put("sharing", null, uid);
                }
                db.commit();
                return ""+n;
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "POST /objects/*/*/share: SQL error:"+e.getMessage(), 500, null);
            }        
            catch (java.lang.NumberFormatException e) {
                return ABORT(resp, db, "POST /objects/*/*/share: Object id must be numeric", 400, null);
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
         
        put("/objects/*/*", (req, resp) -> {
            String tag = req.splat()[0];
            String id = req.splat()[1];
                
            /* Note: this is JSON but we do NOT deserialize it. We store it. */
            String data = req.body();   
                
            /* Get user info */
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");
                
            MyDBSession db = _dbp.getDB();
            try {
                long ident = Long.parseLong(id);
                db.updateJsObject(ident, data);
                _psub.put("object", tag, auth.userid);
                db.commit();
                return "Ok";
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "PUT /objects/"+tag+"/"+id+": SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close(); }
            
        });
        
        
        
        /***************************************************************************
         * REST Service: 
         * Delete an object for the logged in user.
         * Return number of objects actually deleted from database. 
         ***************************************************************************/
         
        delete("/objects/*/*", (req, resp) -> {
            String tag = req.splat()[0];
            String id = req.splat()[1];
            
            /* Get user info */
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");
            
            MyDBSession db = _dbp.getDB();
            try {
                long ident = Long.parseLong(id);
                int n = db.unlinkJsObject(ident, auth.userid, auth.userid);              
                _psub.put("object", tag, auth.userid);
                db.commit();
                return ""+n;
            }
            catch (java.lang.NumberFormatException e) {
                return ABORT(resp, db, "DELETE /objects/"+tag+"/"
                    +id+": Expected numeric object identifier", 
                    400, "Expected numeric object identifier");
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "DELETE /users/"+tag+"/"+id+": SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close();}
        } );
                
        
        /***************************************************************************** 
         * REST Service
         * Get a single (raw text) object. 
         *****************************************************************************/
         
        get("/objects/*/*", "application/json", (req, resp) -> {
            String tag = req.splat()[0];
            String id = req.splat()[1];
            
            /* Get user info */
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");
            
            MyDBSession db = _dbp.getDB();
            try {
                String a =  db.getJsObject(auth.userid, tag, Long.parseLong(id));            
                if (a == null)
                    return ABORT(resp, db, "GET /objects/"+tag+"/"+id+": Item not found: ",
                        404, "Item not found: "+tag+": "+id);
                db.commit();
                return a;
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "GET /objects/"+tag+"/"+id+": SQL error:"+e.getMessage(),
                    500, null);
            }      
            catch (java.lang.NumberFormatException e) {
                return ABORT(resp, db, "GET /objects/*/*: Object id must be numeric", 400, null);
            }
            finally { 
                db.close(); 
            }

        } );
        
        
                
        /***************************************************************************** 
         * REST Service
         * Get a list of (JSON) objects for the logged in user. 
         *****************************************************************************/
         
        get("/objects/*", "application/json", (req, resp) -> {
            String tag = req.splat()[0];
            
            /* Get user info */
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");
            
            MyDBSession db = _dbp.getDB();
            DbList<JsObject> a = null; 
            try {
                a =  db.getJsObjects(auth.userid, auth.groupid, tag);
                List<JsObject> aa = a.toList().stream().collect(Collectors.toList());
                db.commit();
                return aa;
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "GET /objects/"+tag+": SQL error:"+e.getMessage(),
                    500, null);
            }
            finally { 
                db.close(); 
            }

        }, ServerBase::toJson );
     
                
                
        /************************************************************************** 
         * REST Service: 
         * Add an object for the logged in user. 
         * We assume that this is a JSON object but do not parse it. 
         **************************************************************************/
         
        post("/objects/*", (req, resp) -> {
            String tag = req.splat()[0];
            
            /* Get user info */
            var auth = getAuthInfo(req); 
            if (auth == null)
                return ERROR(resp, 500, "No authorization info found");
        
            /* Note: this is JSON but we do NOT deserialize it. We store it. */
            String data = req.body(); 
            
            MyDBSession db = _dbp.getDB();
            try {
                long id = db.addJsObject(auth.userid, tag, data);
                _psub.put("object", tag, auth.userid);
                db.commit();
                return ""+id;
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "POST /objects/"+tag+": SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close(); }
        });
    
    }

   
   
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
    
    
    
    public void removeItem(String id) {
        TrackerPoint pt = _api.getDB().getItem(id, null, false);
        if (pt != null)
            pt.removeTag("MANAGED");
    }
    
   
    

}
