
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
      


    
    /** 
     * Set up the webservices. 
     */
    public void start() {   
    
        _api.getWebserver().corsEnable("/trackers/*");
        _api.getWebserver().corsEnable("/objects/*");
        
        
        /**************************************************************************** 
         * REST Service
         * Get "my trackers" for a given user. 
         ****************************************************************************/
         
        get("/trackers/*", "application/json", (req, resp) -> {
            String uid = req.splat()[0];
            MyDBSession db = _dbp.getDB();
            DbList<Tracker> tr = null; 
            
            try {
                tr =  db.getTrackers(uid);
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
         * Save a tracker for a given user. Update if it exists in the database 
         * and is owned by the user. 
         ****************************************************************************/
         
        post("/trackers/*", (req, resp) -> {
            String uid = req.splat()[0];
            MyDBSession db = _dbp.getDB();
            Tracker.Info tr = (Tracker.Info) 
                ServerBase.fromJson(req.body(), Tracker.Info.class);
            try {
                if (tr==null) 
                    return ABORT(resp, db, "POST /users/*/trackers: cannot parse input", 
                        500, "Cannot parse input");
                
                tr.id = tr.id.toUpperCase();
                Tracker dbtr = db.getTracker(tr.id);
                
                if (dbtr == null)                     
                    db.addTracker(tr.id, tr.user, tr.alias, tr.icon);
   
                /* If we own the tracker, we can update it */
                else if (tr.user.equals(dbtr.info.user)) 
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
         * Delete a tracker for a given user. 
         **************************************************************************/
         
        delete("/trackers/*/*", (req, resp) -> {
            String uid = req.splat()[0];
            String call = req.splat()[1];
            MyDBSession db = _dbp.getDB();
            try {
                db.deleteTracker(call);
                updateItem(call, null, null, req);
                removeItem(call);
                db.commit();
                return "OK";
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "DELETE /users/*/trackers/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close();}  
        } );
        
        
        
        /************************************************************************** 
         * REST Service: 
         * Add an object for a given user. 
         * We assume that this is a JSON object but do not parse it. 
         **************************************************************************/
         
        post("/objects/*/*", (req, resp) -> {
            String uid = req.splat()[0];
            String tag = req.splat()[1];
            MyDBSession db = _dbp.getDB();
            
            // Note: this is JSON but we do NOT deserialize it. 
            String data = req.body(); 
        
            try {
                long id = db.addJsObject(uid, tag, data);
                db.commit();
                return ""+id;
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "POST /users/"+uid+"/"+tag+": SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close(); }
        });
        
        
        
        /***************************************************************************
         * REST Service: 
         * Delete an object for a given user.
         * FIXME: Sanitize input? 
         ***************************************************************************/
         
        delete("/objects/*/*/*", (req, resp) -> {
            String uid = req.splat()[0];
            String tag = req.splat()[1];
            String id = req.splat()[2];
            MyDBSession db = _dbp.getDB();
            
            try {
                long ident = Long.parseLong(id);
                db.deleteJsObject(uid, tag, ident);
                db.commit();
                return "OK";
            }
            catch (java.lang.NumberFormatException e) {
                return ABORT(resp, db, "DELETE /users/"+uid+"/"+tag+"/"
                    +id+": Expected numeric object identifier", 
                    400, "Expected numeric object identifier");
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "DELETE /users/"+uid+"/"+tag+"/"+id+": SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close();}
        } );
        
        
        
        /***************************************************************************** 
         * REST Service
         * Get a list of (JSON) objects for a given user. 
         *****************************************************************************/
         
        get("/objects/*/*", "application/json", (req, resp) -> {
            String uid = req.splat()[0];
            String tag = req.splat()[1];
            MyDBSession db = _dbp.getDB();
            DbList<JsObject> a = null; 
            
            try {
                a =  db.getJsObjects(uid, tag);
                List<JsObject> aa = a.toList().stream().collect(Collectors.toList());
                db.commit();
                return aa;
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "GET /users/"+uid+"/"+tag+": SQL error:"+e.getMessage(),
                    500, null);
            }
            finally { 
                db.close(); 
            }

        }, ServerBase::toJson );
        
        
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
        pt.removeTag("MANAGED");
    }
    
   
    

}
