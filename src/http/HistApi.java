
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
import java.util.regex.*;
import java.time.*;



/*
 * This will eventually replace the XML service for trail and point cloud. 
 * see XMLserver.java
 */
 
public class HistApi extends ServerBase implements JsonPoints
{
    private ServerAPI _api; 
    private PluginApi _dbp;
    private ColourTable _colTab = null;
    private HashMap<String, String[]> _colUsed = new HashMap<String,String []>();
    public java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
    
            
    public HistApi(ServerAPI api) {
        super (api); 
        _api = api;
        _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
        _colTab = new ColourTable (api, System.getProperties().getProperty("confdir", ".")+"/trailcolours");
    }
    
    
    public class RawPacket {
        public Date time;
        public String source, from, to;
        public String via, report;
        
        public RawPacket(Date tm, String src, String fr, String to, String via, String rep)
         {time=tm; source=src; from=fr; this.to=to; this.via=via; report=rep;}
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
      

    public Date parseIsoDf(String d) {
        Instant timestamp = Instant.parse(d);
        ZonedDateTime local = timestamp.atZone(ZoneId.systemDefault());
        return Date.from(local.toInstant());
    }
    
    
    
    /** 
     * Set up the webservices. 
     */
    public void start() {   
    
        _api.getWebserver().corsEnable("/hist/*");
    
        /**************************************************************************
         * REST service:  
         * /hist/<callsign>/aprs?n=..   
         * Get APRS raw packets for a given callsign.
         *   - n - return the n last packets. 
         *   - tto - to date/time
         *   - tfrom from date/time
         * Number is given as request parameter n 
         **************************************************************************/
        
        get("/hist/*/aprs", "application/json", (req, resp) -> {
            
            String src = req.splat()[0].toUpperCase();
            QueryParamsMap parms = req.queryMap();
            MyDBSession db = _dbp.getDB();
            
            try {
                String nn = parms.value("n");
                int n = 25; 
                if (nn != null)
                    n = Integer.parseInt(nn);
                
                Date dto = null;
                String dtos = parms.value("tto"); 
                if (dtos == null || "-/-".equals(dtos))
                    dto = new Date();
                else
                    dto = parseIsoDf(dtos);
                
                Date dfrom = null;
                String dfroms = parms.value("tfrom"); 
                if (dfroms != null) 
                    dfrom = parseIsoDf(dfroms);
                    
                DbList<AprsPacket> list = db.getAprsPackets(src, n, dto, dfrom);
                List<RawPacket> res = new ArrayList<RawPacket>(); 
                for (AprsPacket x: list)
                    res.add(new RawPacket(x.time, x.source.getIdent(), x.from, x.to, x.via, x.report));
                db.commit();
                return res;
            }    
            catch(java.lang.NumberFormatException e) {  
                return ABORT(resp, db, "GET /hist/*/aprs: Cannot parse number", 400,  null);
            }
            catch(java.sql.SQLException e) { 
                return ABORT(resp, db, "GET /hist/*/aprs: SQL error:"+e.getMessage(), 500, null); 
            }
            catch(Exception e) { 
                e.printStackTrace(System.out);
                return ABORT(resp, db, "GET /hist/*/aprs: Exception:"+e, 500, null); 
            }
            finally { db.close(); }
        }, ServerBase::toJson );
         
         
    
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
                Date dfrom = parseIsoDf(parms.value("tfrom"));
                Date dto = null; 
                if (parms.value("tto").equals("-/-"))
                    dto = new Date(); 
                else
                    dto = parseIsoDf(parms.value("tto"));
            
                TrackerPoint tp = db.getItem(src, dto);           
                /* Get tags from db */
                setTags(db, tp, dto);
                
                var auth = getAuthInfo(req);
                boolean aliasAuth = auth.sar || auth.admin;
                
                /* Get alias and/or icon */
                if (aliasAuth || (auth.group != null && tp.hasTag(auth.group.getTags())))
                    db.getAnnotations(tp, dto);
                            
                DbList<TPoint> h = db.getTrail(src, dfrom, dto);
          
                mu = new JsOverlay("HISTORICAL");
                JsPoint p = createPoint(tp, true, null);
                if (p==null)
                    return ABORT(resp, db, "GET /hist/*/trail: Point not found", 404,  null);
                p.trail = createTrail(tp, h, null, true); 
                mu.points = new LinkedList<JsPoint>();
                mu.points.add(p);
                db.commit();
                return mu;
            }
            catch(java.sql.SQLException e) { 
                return ABORT(resp, db, "GET /hist/*/trail: SQL error: "+e.getMessage(), 500, 
                   "SQL Error"); 
            }            
            catch(Exception e) { 
                e.printStackTrace(System.out);
                return ABORT(resp, db, "GET /hist/*/trail: Error: "+e.getMessage(), 500, 
                   e.getMessage()); 
            }
            finally { db.close(); }
        }, ServerBase::toJson );
        
        
        
        
        
        get("/hist/snapshot/*/*/*/*", "application/json", (req, resp) -> {
            QueryParamsMap parms = req.queryMap();
            MyDBSession db = _dbp.getDB();
            JsOverlay mu = null;
            TrackerPoint tp = null;
            String curr_ident = "XXXXX";                
            var uid = getAuthInfo(req).userid; 
            
            try {
                double x1 = Double.parseDouble(req.splat()[0]);
                double x2 = Double.parseDouble(req.splat()[1]);
                double x3 = Double.parseDouble(req.splat()[2]);
                double x4 = Double.parseDouble(req.splat()[3]);
                if (x1 > 180.0) x1 = 180.0; if (x1 < -180.0) x1 = -180.0;
                if (x2 > 180.0) x2 = 180.0; if (x2 < -180.0) x2 = -180.0;
                if (x3 > 90.0) x3 = 90.0; if (x3 < -90.0) x3 = -90.0;
                if (x4 > 90.0) x4 = 90.0; if (x4 < -90.0) x4 = -90.0;
                Reference uleft  = new LatLng((double) x4, (double) x1); 
                Reference lright = new LatLng((double) x2, (double) x3);
                
                Date dto = parseIsoDf(parms.value("tto"));
                double scale = Double.parseDouble(parms.value("scale"));
                String filt = parms.value("filter");
                RuleSet vfilt = ViewFilter.getFilter(filt, uid != null); 
                boolean reset = (parms.value("reset") != null);
                
                var auth = getAuthInfo(req);
                boolean aliasAuth = auth.sar || auth.admin;
                
                mu = new JsOverlay("HISTORICAL");
                mu.points = new LinkedList<JsPoint>();
                DbList<MyDBSession.TrailItem> items = db.getTrailsAt(uleft, lright, dto);
                List<TPoint> trail = new ArrayList<TPoint>();
                
                for (MyDBSession.TrailItem it : items) {
                    if (!curr_ident.equals(it.ident)) {
                        processPoint(mu, tp, trail, vfilt, scale, reset);
                        tp = it.toPoint();
                        
                        /* Get tags from db */
                        setTags(db, tp, dto);
                        
                        /* Get alias and/or icon */
                        if (aliasAuth || (auth.group != null && tp.hasTag(auth.group.getTags())))
                            db.getAnnotations(tp, dto);
                        
                        trail.clear();
                        curr_ident = it.ident;
                    }
                    else {
                        TPoint x = new TPoint(it.time, it.pos, it.path);
                        if (accept_tpoint(x, tp, dto))
                            trail.add(x);
                    }
                }
                processPoint(mu, tp, trail, vfilt, scale, reset);
                db.commit();
                return mu;
            }
            catch(java.sql.SQLException e) { 
                return ABORT(resp, db, "GET /hist/snapshot: SQL error: "+e.getMessage(), 500, 
                    "SQL Error"); 
            }            
            catch(Exception e) { 
                e.printStackTrace(System.out);
                return ABORT(resp, db, "GET /hist/snapshot: Error: "+e.getMessage(), 500, 
                    "Exception - check log"); 
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
                String tfrom = parms.value("tfrom");
                String tto = parms.value("tto");
                Date dfrom = parseIsoDf(tfrom);
                Date dto = null; 
                if (tto.equals("-/-"))
                    dto = new Date(); 
                else
                    dto = parseIsoDf(tto);

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
            catch(java.sql.SQLException e) { 
                return ABORT(resp, db, "GET /hist/*/hrdvia: SQL error:"+e.getMessage(), 500, null); 
            }  
            catch(Exception e) { 
                e.printStackTrace(System.out);
                return ABORT(resp, db, "GET /hist/*/hrdvia: Error:"+e.getMessage(), 500, null); 
            }      
            finally { db.close(); }
        }, ServerBase::toJson );       
    
    }
        
        
        
    private void addToTrail(TrackerPoint pt, MyDBSession.TrailItem it) {
        /* TBD */
    }
    
    
    
    private void setTags(MyDBSession db, TrackerPoint tp, Date dto) 
        throws java.sql.SQLException
    {
        DbList<String> tags = db.getTagsAt(tp.getIdent(), dto);
        for (String t : tags)
            tp.setTag(t);
    }
    
               
               
    int trail_maxpause = 15; // FIXME: Use config
    int trail_maxage = 25;   // FIXME: Use config
            
    private boolean accept_tpoint(TPoint p, TrackerPoint tp, Date dto) {
        if ( tp.getUpdated().getTime()  < dto.getTime() - trail_maxpause*1000*60 ||
             p.getTS().getTime() <  tp.getUpdated().getTime() - trail_maxage*1000*60)  
            return false; 
        return true;
    }
    
    
    
    
    private void processPoint(JsOverlay mu, TrackerPoint tp, List<TPoint> trail, 
                    RuleSet vfilt, double scale, boolean reset) {
        if (tp != null) {
            Action action = vfilt.apply(tp, (long) scale); 
            if (!action.hideAll()) {
                JsPoint p = createPoint(tp, trail.size() > 1, action);        
                p.trail = createTrail(tp, trail, action, reset);
                mu.points.add(p);
            }
        }
    }
   
   
   
    /** 
     * Convert Tracker point to JSON point. 
     * Return null if point has no position.  
     */
    private JsPoint createPoint(TrackerPoint s, boolean moving, Action action) {
        Reference rref = s.getPosition();
        LatLng ref; 
        if (rref == null) 
            return null;
        ref=rref.toLatLng();  
        JsPoint x  = new JsPoint();
        x.ident  = s.getDisplayId();
        x.label  = createLabel(s, moving, action);
        x.pos    = new double[] {ref.getLongitude(), ref.getLatitude()};
        
        String icon = s.getIcon(true); 
        x.icon = "/icons/"+ icon; 
        return x;
    }
   
   
   
    /** Create label or return null if label is to be hidden. */
    private JsLabel createLabel(TrackerPoint s, boolean moving, Action action) {
        JsLabel lbl = new JsLabel();
    
        lbl.style = (moving ? "lmoving" : "lstill");
        if (s instanceof AprsObject)
            lbl.style = "lobject"; 
        lbl.id = s.getDisplayId(true);
        if (action!=null) 
            lbl.hidden = action.hideIdent();
        return lbl;
    }
   
   
   
    private JsTrail createTrail(TrackerPoint s, Iterable<TPoint> h, Action action, boolean reset) {
    
        String[] col = null;
        if (!reset) 
            col = _colUsed.get(s.getIdent());
        if (col==null) {
            col = s.getTrailColor();
            _colUsed.put(s.getIdent(), col);
        }
        
        JsTrail res = new JsTrail(col); 
        h.forEach( it -> res.linestring.add(new JsTPoint(it) ) );
        return res;
    }


}
