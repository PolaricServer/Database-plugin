
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
      
      
    
    /** 
     * Set up the webservices. 
     */
    public void start() {   
        
        _api.getWebserver().corsEnable("/hist/*");
    
        /* 
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
        
        
        
        
        
        /* Get "my trackers" for a given user. */
        get("/users/*/trackers", "application/json", (req, resp) -> {
            String uid = req.splat()[0];
            MyDBSession db = _dbp.getDB();
            DbList<Tracker> tr = null; 
            
            try {
                tr =  db.getTrackers(uid);
                List<Tracker.Info> tri = tr.toList().stream().map(x -> x.info).collect(Collectors.toList());
                for (Tracker.Info x: tri) 
                    System.out.println("*** "+x.id);
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
        
        
        
        
        
        /* Save a tracker for a given user. Update if it exists in the database. */      
        put("/users/*/trackers", (req, resp) -> {
            String uid = req.splat()[0];
            MyDBSession db = _dbp.getDB();
            Tracker.Info tr = (Tracker.Info) 
                ServerBase.fromJson(req.body(), HistSearch.class);
            try {
                db.addTracker(tr.id, tr.user, tr.alias, tr.icon);
                db.commit();
                return "OK";
            }
            catch (java.sql.SQLException e) {
                _dbp.log().warn("JsonApi", "PUT /users/*/trackers: SQL error:"+e.getMessage());
                db.abort();
                return ERROR(resp, 500, "SQL error: "+e.getMessage());
            }
            finally { db.close(); }
            
        } );
        
        
        
        
        delete("/users/*/trackers/*", (req, resp) -> {
            String uid = req.splat()[0];
            String call = req.splat()[1];
            MyDBSession db = _dbp.getDB();
            try {
                db.deleteTracker(call);
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
