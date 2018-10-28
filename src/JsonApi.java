
package no.polaric.aprsdb;
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


/*
 * This will eventually replace the XML service for trail and point cloud. 
 * see XMLserver.java
 */
 
public class JsonApi implements JsonPoints
{
    private ServerAPI _api; 
    private DatabasePlugin _dbp;
    public java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
       
    public JsonApi(ServerAPI api) {
        _api = api;
        _dbp = (DatabasePlugin) api.properties().get("aprsdb.plugin");
    }
        
        
    /** 
     * Return an error status message to client 
     */
    public String ERROR(Response resp, int status, String msg)
      { resp.status(status); return msg; }
      
      
    // FIXME: Move to StationDB? 
    public TrackerPoint updateItem(String id, String alias, String icon) {
        TrackerPoint pt = _api.getDB().getItem(id, null, false);
        if (pt != null) {
            _dbp.log().debug("JsonApi", "Update tracker "+id+": alias="+alias);
            pt.setIcon(icon); 
            pt.setAlias(alias);
            // FIXME: Send update messages to peer systems
        }
        return pt; 
    }
    
    
    /** 
     * Set up the webservices. 
     */
    public void start() {   
        
        _api.getWebserver().corsEnable("/hist/*");
        
    
        /* REST service:  
         * /hist/<callsign>/trail?tfrom=...&tto=...   
         * Get historical trail for a given callsign. 
         * Timespan is given as request parameters tfrom and tto 
         */
        get("/hist/*/trail", "application/json", (req, resp) -> {
            String src = req.splat()[0].toUpperCase();
            QueryParamsMap parms = req.queryMap();
            MyDBSession db = _dbp.getDB();
            JsOverlay mu = null;
            
            try {
                Date dfrom = df.parse(parms.value("tfrom"));
                Date dto = null; 
                if (parms.value("tto").equals("-/-"))
                    dto = new Date(); 
                else
                    dto = df.parse(parms.value("tto"));
            
                Station s = (Station) db.getItem(src, dto);
                DbList<TPoint> h = db.getTrail(src, dfrom, dto);
          
                mu = new JsOverlay("HISTORICAL");
                JsPoint p = createPoint(s);
                p.trail = createTrail(s, h); 
                mu.points = new LinkedList<JsPoint>();
                mu.points.add(p); 
            }
            catch(java.text.ParseException e) { 
                _dbp.log().warn("JsonApi", "GET /hist/*/trail: Cannot parse timestring"); 
                db.abort(); 
            }
            catch(java.sql.SQLException e) { 
                _dbp.log().warn("JsonApi", "GET /hist/*/trail: SQL error:"+e.getMessage()); 
                db.abort(); 
            }
            finally { db.close(); return mu; }
        }, ServerBase::toJson );
        
        
        
        
        
        /* 
         * REST Service
         * Get "my trackers" for a given user. 
         */
        get("/users/*/trackers", "application/json", (req, resp) -> {
            String uid = req.splat()[0];
            MyDBSession db = _dbp.getDB();
            DbList<Tracker> tr = null; 
            
            try {
                tr =  db.getTrackers(uid);
                List<Tracker.Info> tri = tr.toList().stream().map(x -> x.info).collect(Collectors.toList());
                return tri;
            }
            catch (java.sql.SQLException e) {
                _dbp.log().warn("JsonApi", "GET /users/*/trackers: SQL error:"+e.getMessage());
                db.abort();
                return null;
            }
            finally { 
                db.close(); 
            }

        }, ServerBase::toJson );
        
        
        
        
        
        /* 
         * REST Service: 
         * Save a tracker for a given user. Update if it exists in the database and is owned by the user. 
         */      
        post("/users/*/trackers", (req, resp) -> {
            String uid = req.splat()[0];
            MyDBSession db = _dbp.getDB();
            Tracker.Info tr = (Tracker.Info) 
                ServerBase.fromJson(req.body(), Tracker.Info.class);
            try {
                if (tr==null) {
                    db.abort();
                    _dbp.log().warn("JsonApi", "POST /users/*/trackers: cannot parse input");
                    return ERROR(resp, 500, "Cannot parse input");
                }
                tr.id = tr.id.toUpperCase();
                Tracker dbtr = db.getTracker(tr.id);
                
                if (dbtr == null)                     
                    db.addTracker(tr.id, tr.user, tr.alias, tr.icon);
   
                /* If we own the tracker, we can update it */
                else if (tr.user.equals(dbtr.info.user)) 
                    db.updateTracker(tr.id, tr.alias, tr.icon);
                else {
                    db.abort();
                    return ERROR(resp, 403, "Item is owned by another user");
                }
                var pt = updateItem(tr.id, tr.alias, tr.icon);
                db.commit();
                return (pt==null ? "OK" : "OK-ACTIVE");
            }
            catch (java.sql.SQLException e) {
                db.abort();
                _dbp.log().warn("JsonApi", "POST /users/*/trackers: SQL error:"+e.getMessage());
                return ERROR(resp, 500, "SQL error: "+e.getMessage());
            }
            finally { db.close(); }
        } );
        
        
        
        /*
         * REST Service: 
         * Delete a tracker for a given user. 
         */
        delete("/users/*/trackers/*", (req, resp) -> {
            String uid = req.splat()[0];
            String call = req.splat()[1];
            MyDBSession db = _dbp.getDB();
            try {
                db.deleteTracker(call);
                updateItem(call, null, null);
                db.commit();
                return "OK";
            }
            catch (java.sql.SQLException e) {
                _dbp.log().warn("JsonApi", "DELETE /users/*/trackers/*: SQL error:"+e.getMessage());
                db.abort();
                return ERROR(resp, 500, "SQL error: "+e.getMessage());
            }
            finally { db.close();}
            
        } );
        
        
        /* 
         * REST Service: 
         * Add an object for a given user
         */
        post("/users/*/*", (req, resp) -> {
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
                _dbp.log().warn("JsonApi", "POST /users/"+uid+"/"+tag+": SQL error:"+e.getMessage());
                db.abort();
                return ERROR(resp, 500, "SQL error: "+e.getMessage());
            }
            finally { db.close(); }
        });
        
        
        
        /*
         * REST Service: 
         * Delete a tracker for a given user.
         * FIXME: Sanitize input? 
         */
        delete("/users/*/*/*", (req, resp) -> {
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
                _dbp.log().warn("JsonApi", "DELETE /users/"+uid+"/"+tag+"/"
                    +id+": Expected numeric object identifier");
                db.abort();
                return ERROR(resp, 400, "Expected numeric object identifier");
            }
            catch (java.sql.SQLException e) {
                _dbp.log().warn("JsonApi", "DELETE /users/"+uid+"/"+tag+"/"
                    +id+": SQL error:"+e.getMessage());
                db.abort();
                return ERROR(resp, 500, "SQL error: "+e.getMessage());
            }
            finally { db.close();}
        } );
        
        
        /* 
         * REST Service
         * Get a list of objects for a given user. 
         */
        get("/users/*/*", "application/json", (req, resp) -> {
            String uid = req.splat()[0];
            String tag = req.splat()[1];
            MyDBSession db = _dbp.getDB();
            DbList<JsObject> a = null; 
            
            try {
                a =  db.getJsObjects(uid, tag);
                List<JsObject> aa = a.toList().stream().collect(Collectors.toList());
                return aa;
            }
            catch (java.sql.SQLException e) {
                _dbp.log().warn("JsonApi", "GET /users/"+uid+"/"+tag+": SQL error:"+e.getMessage());
                db.abort();
                return ERROR(resp, 500, "SQL error: "+e.getMessage());
            }
            finally { 
                db.close(); 
            }

        }, ServerBase::toJson );
        
        
    }


   
    /** 
     * Convert Tracker point to JSON point. 
     * Return null if point has no position.  
     */
    private JsPoint createPoint(TrackerPoint s) {
        LatLng ref = s.getPosition().toLatLng(); 
        if (ref == null) 
            return null;
         
        JsPoint x  = new JsPoint();
        x.ident  = s.getIdent();
        x.label  = createLabel(s);
        x.pos    = new double[] {ref.getLongitude(), ref.getLatitude()};
       
        String icon = s.getIcon(); 
        x.icon = "/icons/"+ icon; 
        return x;
    }
   
   
   
    /** Create label or return null if label is to be hidden. */
    private JsLabel createLabel(TrackerPoint s) {
        JsLabel lbl = new JsLabel();
    
        lbl.style = "lmoving";
        if (s instanceof AprsObject)
            lbl.style = "lobject"; 
        lbl.id = s.getDisplayId(false);
        return lbl;
    }
   
   
   
    private JsTrail createTrail(TrackerPoint s, DbList<TPoint> h) {

        if (!h.isEmpty()) {
            JsTrail res = new JsTrail(s.getTrailColor()); 
            h.forEach( it -> res.linestring.add(new JsTPoint(it) ) );
            return res;
        }
        else return null;
    }

    

}
