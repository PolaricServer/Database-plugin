
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
 
public class HistApi extends ServerBase implements JsonPoints
{
    private ServerAPI _api; 
    private PluginApi _dbp;
    public java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
       
    public HistApi(ServerAPI api) {
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
        _dbp.log().warn("HistApi", logmsg);
        db.abort();
        return ERROR(resp, status, msg);
    }
      

    
    
    /** 
     * Set up the webservices. 
     */
    public void start() {   
    
        _api.getWebserver().corsEnable("/hist/*");
    
    
    
        /**************************************************************************
         * REST service:  
         * /hist/<callsign>/trail?tfrom=...&tto=...   
         * Get historical trail for a given callsign. 
         * Timespan is given as request parameters tfrom and tto 
         **************************************************************************/
         
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
                if (p==null)
                    return ABORT(resp, db, "GET /hist/*/trail: Point not found", 404,  null);
                p.trail = createTrail(s, h); 
                mu.points = new LinkedList<JsPoint>();
                mu.points.add(p);
                db.commit();
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
        
        
     
        /************************************************************************* 
         * REST service:  
         * /hist/<callsign>/hrdvia?tfrom=...&tto=...   
         * Get heard points for a given callsign. 
         * Timespan is given as request parameters tfrom and tto 
         *************************************************************************/
         
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
                db.commit();
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
    
    }
        
        

   
    /** 
     * Convert Tracker point to JSON point. 
     * Return null if point has no position.  
     */
    private JsPoint createPoint(TrackerPoint s) {
        Reference rref = s.getPosition();
        LatLng ref; 
        if (rref == null) 
            return null;
        ref=rref.toLatLng();  
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
