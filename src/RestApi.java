
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
import javax.servlet.*;
import javax.servlet.http.*;
import org.eclipse.jetty.server.*;


/*
 * This will eventually replace the XML service for trail and point cloud. 
 * see XMLserver.java
 */
 
public class RestApi implements JsonPoints
{
    private ServerAPI _api; 
    private DatabasePlugin _dbp;
    public java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
       
    public RestApi(ServerAPI api) {
        _api = api;
        _dbp = (DatabasePlugin) api.properties().get("aprsdb.plugin");
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
      
      
      
    // FIXME: Move to StationDB? 
    public TrackerPoint updateItem(String id, String alias, String icon) {
        TrackerPoint pt = _api.getDB().getItem(id, null, false);
        if (pt != null) {
            _dbp.log().debug("RestApi", "Update tracker "+id+": alias="+alias);
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
        _api.getWebserver().protectUrl("/files/*");
        _api.getWebserver().corsEnable("/hist/*");
        _api.getWebserver().corsEnable("/files/*");
    
    
    
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
                return mu;
            }
            catch(java.text.ParseException e) {  
                return ABORT(resp, db, "GET /hist/*/trail: Cannot parse timestring", 500,  null);
            }
            catch(java.sql.SQLException e) { 
                return ABORT(resp, db, "GET /hist/*/trail: SQL error:"+e.getMessage(), 500, null); 
            }
            finally { db.close(); }
        }, ServerBase::toJson );
        
        
     
        /* REST service:  
         * /hist/<callsign>/hrdvia?tfrom=...&tto=...   
         * Get historical trail for a given callsign. 
         * Timespan is given as request parameters tfrom and tto 
         */
        get("/hist/*/hrdvia", "application/json", (req, resp) -> {
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
                DbList<TPoint> h = db.getPointsVia(src, null, null, dfrom, dto);          
                mu = new JsOverlay("HEARD-VIA");
                
                /* 
                 * Convert list of TPoint's to a list of 
                 * JSTPoint's on the returned overlay JSON object. 
                 * FIXME: DB search should return a list of JsTPoint directly. 
                 */
                mu.pcloud = new ArrayList<JsTPoint>(); 
                for (TPoint x : h) 
                    mu.pcloud.add(new JsTPoint(x));
                return mu;
            }
            catch(java.text.ParseException e) {  
                return ABORT(resp, db, "GET /hist/*/hrdvia: Cannot parse timestring", 500,  null);
            }
            catch(java.sql.SQLException e) { 
                return ABORT(resp, db, "GET /hist/*/hrdvia: SQL error:"+e.getMessage(), 500, null); 
            }            
            finally { db.close(); }
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
                return ABORT(resp, db, "GET /users/*/trackers: SQL error:"+e.getMessage(), 500, null);
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
                var pt = updateItem(tr.id, tr.alias, tr.icon);
                db.commit();
                return (pt==null ? "OK" : "OK-ACTIVE");
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "POST /users/*/trackers: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
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
                return ABORT(resp, db, "DELETE /users/*/trackers/*: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close();}
            
        } );
        
        
        /* 
         * REST Service: 
         * Add an object for a given user. 
         * We assume that this is a JSON object but do not parse it. 
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
                return ABORT(resp, db, "POST /users/"+uid+"/"+tag+": SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            finally { db.close(); }
        });
        
        
        
        /*
         * REST Service: 
         * Delete an object for a given user.
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
        
        
        /* 
         * REST Service
         * Get a list of (JSON) objects for a given user. 
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
                return ABORT(resp, db, "GET /users/"+uid+"/"+tag+": SQL error:"+e.getMessage(),
                    500, null);
            }
            finally { 
                db.close(); 
            }

        }, ServerBase::toJson );
        
        
        
        get("/files/gpx/*", "text/gpx+xml", (req, resp) -> {
            String fid = req.splat()[0];
            MyDBSession db = _dbp.getDB();
            try {
                resp.header("cache-control", "max-age=3600"); /* 1 hour cache */
                long id = Long.parseLong(fid); 
                return db.getFileObject(id);
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "GET /files/gpx/"+fid+": SQL error:"+e.getMessage(), 500, null);
            }
            catch (NumberFormatException e) {
                return ABORT(resp, db,  "GET /files/gpx/"+fid+": Last part of url should be number",
                    500, null);
            }
            finally { 
                db.close(); 
            }
        });
             
        
        
        /* Note: This expects multipart form data */
        post("/files/gpx", (req, resp) -> {
            MyDBSession db = _dbp.getDB();
            req.attribute("org.eclipse.jetty.multipartConfig", new MultipartConfigElement(""));
            
            try {        
                List<Long> ids = new ArrayList<Long>(); 
                java.util.Collection<Part> parts = req.raw().getParts(); 
                for (Part p: parts) {
                    var type = p.getContentType();
                    var file = p.getSubmittedFileName();
                    if (type != null && (
                        (type.equals("application/gpx+xml")) || type.equals("application/x-gpx+xml") ||
                        (file.substring(file.lastIndexOf(".")+1).equals("gpx"))
                    )) {
                        _dbp.log().info("RestApi", "Upload file: name="+file+", size="+p.getSize());
                        ids.add( db.addFileObject(p.getInputStream()) );
                    }
                    else
                        return ABORT(resp, db, "post/files/gpx/: Unsupported file type: "+type, 
                            415, "Unsupported file type (should be GPX)");
                }
                return ids;
            }
            catch(java.sql.SQLException e) {
                return ABORT(resp, db, "POST /files/gpx/: SQL error: "+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }      
            catch(Exception e) {
                e.printStackTrace(System.out);
                return ABORT(resp, db, "POST /files/gpx/: Strange server error: "+e.getMessage(),
                    500, "Server error: "+e.getMessage());
            }
            finally {
                db.close();
            }
        }, ServerBase::toJson );
        
        
        
        
        delete("/files/gpx/*", (req, resp) -> {
            String fid = req.splat()[0];
            MyDBSession db = _dbp.getDB();
            try {
                long id = Long.parseLong(fid); 
                db.deleteFileObject(id);
                return "OK";
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "DELETE /files/gpx/"+fid+": SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            catch (NumberFormatException e) {
                return ABORT(resp, db,  "DELETE /files/gpx/"+fid+": Last part of url should be number",
                    500, "Expected numeric input");
            }
            finally { 
                db.close(); 
            }
        });
        
        
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
