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
import no.polaric.core.*;
import no.polaric.core.httpd.*;
import no.polaric.core.auth.*;
import io.javalin.Javalin;
import io.javalin.http.Context;

import no.polaric.aprsdb.*;
import no.polaric.aprsd.*;
import no.polaric.aprsd.aprs.*;
import no.polaric.aprsd.point.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import no.polaric.aprsd.filter.*;
import java.util.stream.Collectors;
import java.util.regex.*;
import java.time.*;
import java.time.format.*;



/*
 * This will eventually replace the XML service for trail and point cloud. 
 * see XMLserver.java
 */
 
public class HistApi extends ServerBase implements JsonPoints
{
    private ServerConfig _api; 
    private PluginApi _dbp;
    private String _defaultIcon;
    private int _photoscale;
    private ColourTable _colTab = null;
    private HashMap<String, String[]> _colUsed = new HashMap<String,String []>();
    public java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
    
            
    public HistApi(ServerConfig api) {
        super (api); 
        _api = api;
        _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
        _colTab = new ColourTable (api, System.getProperties().getProperty("confdir", ".")+"/trailcolours");
        _defaultIcon =  _api.getProperty("map.icon.default", "sym00.png");   
        _photoscale = api.getIntProperty("db.photos.maxscale", 500000);
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
    public Object ERROR(Context ctx, int status, String msg) { 
        if (msg==null) msg = "Error";
        ctx.status(status); ctx.result(msg); 
        return null;
    }
    
      
      
    public Object ABORT(Context ctx, MyDBSession db, String logmsg, int status, String msg) {
        _dbp.log().warn("HistApi", logmsg);
        if (db!=null)
            db.abort();
        return ERROR(ctx, status, msg);
    }
      
    
    public Object S_ABORT(Context ctx, SignsDBSession db, String logmsg, int status, String msg) {
        _dbp.log().warn("HistApi", logmsg);
        if (db!=null)
            db.abort();
        return ERROR(ctx, status, msg);
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
    
        protect("/hist/snapshot/*");
        protect("/hist/*/trail");
        
        
        /**************************************************************************
         * REST service:  
         * /hist/<callsign>/aprs?n=..   
         * Get APRS raw packets for a given callsign.
         *   - n - return the n last packets. 
         *   - tto - to date/time
         *   - tfrom from date/time
         * Number is given as request parameter n 
         **************************************************************************/
        
        a.get("/hist/{src}/aprs", (ctx) -> {
            var src = ctx.pathParam("src").toUpperCase();
            MyDBSession db = _dbp.getDB();
            
            try {
                String nn = ctx.queryParam("n");
                int n = 25; 
                if (nn != null)
                    n = Integer.parseInt(nn);
                
                Date dto = null;
                String dtos = ctx.queryParam("tto"); 
                if (dtos == null || "-/-".equals(dtos))
                    dto = new Date();
                else
                    dto = parseIsoDf(dtos);
                
                Date dfrom = null;
                String dfroms = ctx.queryParam("tfrom"); 
                if (dfroms != null) 
                    dfrom = parseIsoDf(dfroms);
                    
                DbList<AprsPacket> list = db.getAprsPackets(src, n, dto, dfrom);
                List<RawPacket> res = new ArrayList<RawPacket>(); 
                for (AprsPacket x: list)
                    res.add(new RawPacket(x.time, x.source.getIdent(), x.from, x.to, x.via, x.report));
                db.commit();
                ctx.json(res);
            }    
            catch(java.lang.NumberFormatException e) {  
                ABORT(ctx, db, "GET /hist/*/aprs: Cannot parse number", 400,  "Cannot parse number");
            }
            catch(DateTimeParseException e) { 
                ABORT(ctx, db, "GET /hist/*/aprs: Cannot parse date/time:"+e.getMessage(), 400, 
                  "Cannot parse date/time"); 
            } 
            catch(java.sql.SQLException e) { 
                ABORT(ctx, db, "GET /hist/*/aprs: SQL error:"+e.getMessage(), 500, "SQL error"); 
            }
            catch(Exception e) { 
                e.printStackTrace(System.out);
                ABORT(ctx, db, "GET /hist/*/aprs: Exception:"+e, 500, null); 
            }
            finally { db.close(); }
        });
         
         
    
        /**************************************************************************
         * REST service:  
         * /hist/<callsign>/trail?tfrom=...&tto=...   
         * Get historical trail for a given callsign. 
         * Timespan is given as request parameters tfrom and tto 
         **************************************************************************/
         
        a.get("/hist/{src}/trail", (ctx) -> {
            var src = ctx.pathParam("src").toUpperCase();
            MyDBSession db = _dbp.getDB();
            JsOverlay mu = null;
            
            try {
                Date dfrom = parseIsoDf(ctx.queryParam("tfrom"));
                Date dto = null; 
                if (ctx.queryParam("tto").equals("-/-"))
                    dto = new Date(); 
                else
                    dto = parseIsoDf(ctx.queryParam("tto"));
            
                TrackerPoint tp = db.getItem(src, dto);           
                /* Get tags from db */
                setTags(db, tp, dto);
                
                var auth = getAuthInfo(ctx);
                boolean aliasAuth = auth.operator || auth.admin;
                
                /* Get alias and/or icon */
                if (aliasAuth || (auth.group != null && tp.hasTag(auth.group.getTags())))
                    db.getAnnotations(tp, dto);
                            
                DbList<TPoint> h = db.getTrail(src, dfrom, dto);
          
                mu = new JsOverlay("HISTORICAL");
                JsPoint p = createPoint(tp, true, null);
                if (p==null) {
                    ABORT(ctx, db, "GET /hist/*/trail: Point not found", 404,  "Point not found");
                    return;
                }
                p.trail = createTrail(tp, h, null, true); 
                mu.points = new LinkedList<JsPoint>();
                mu.points.add(p);
                db.commit();
                ctx.json(mu);
            }
            catch(DateTimeParseException e) { 
                ABORT(ctx, db, "GET /hist/*/trail: Cannot parse date/time:"+e.getMessage(), 400, 
                  "Cannot parse date/time"); 
            } 
            catch(java.sql.SQLException e) { 
                ABORT(ctx, db, "GET /hist/*/trail: SQL error: "+e.getMessage(), 500, 
                   "SQL Error"); 
            }            
            catch(Exception e) { 
                e.printStackTrace(System.out);
                ABORT(ctx, db, "GET /hist/*/trail: Error: "+e.getMessage(), 500, 
                   e.getMessage()); 
            }
            finally { db.close(); }
        } );
        
        
        
        
        
        a.get("/hist/snapshot/{x1}/{x2}/{x3}/{x4}", (ctx) -> {
            MyDBSession db = _dbp.getDB();
            JsOverlay mu = null;
            TrackerPoint tp = null;
            String curr_ident = "XXXXX";                
            var uid = getAuthInfo(ctx).userid; 
            var group = getAuthInfo(ctx).groupid;
            
            try {
                double x1 = Double.parseDouble(ctx.pathParam("x1"));
                double x2 = Double.parseDouble(ctx.pathParam("x2"));
                double x3 = Double.parseDouble(ctx.pathParam("x3"));
                double x4 = Double.parseDouble(ctx.pathParam("x4"));
                if (x1 > 180.0) x1 = 180.0; if (x1 < -180.0) x1 = -180.0;
                if (x2 > 180.0) x2 = 180.0; if (x2 < -180.0) x2 = -180.0;
                if (x3 > 90.0) x3 = 90.0; if (x3 < -90.0) x3 = -90.0;
                if (x4 > 90.0) x4 = 90.0; if (x4 < -90.0) x4 = -90.0;
                LatLng uleft  = new LatLng((double) x4, (double) x1); 
                LatLng lright = new LatLng((double) x2, (double) x3);
                
                Date dto = parseIsoDf(ctx.queryParam("tto"));
                double scale = Double.parseDouble(ctx.queryParam("scale"));
                String filt = ctx.queryParam("filter");
                RuleSet vfilt = ViewFilter.getFilter(filt, uid != null); 
                boolean reset = (ctx.queryParam("reset") != null);
                
                var auth = getAuthInfo(ctx);
                boolean aliasAuth = auth.operator || auth.admin;
                
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
                        String path = it.path; 
                        if (it.channel.equals("(ext)") || it.channel.equals("(int)"))
                            path = "(ext)";
                        else if (tp instanceof AprsPoint p) {
                            p.setSymtab(it.symtab);
                            p.setSymbol(it.symbol);
                        }

                        TPoint x = new TPoint(it.time, it.pos, path);
                        if (accept_tpoint(x, tp, dto))
                            trail.add(x);
                    }
                }
                processPoint(mu, tp, trail, vfilt, scale, reset);
                db.commit();
                
                /* Search photos */    
                SignsDBSession sdb = new SignsDBSession(_dbp.getDB());
                try {
                    if (scale < _photoscale) {
                        DbList<Signs.Item> photos = sdb.getPhotos(uid, group, dto, uleft, lright);
                        for (Signs.Item x: photos)
                            processPhoto(mu, x);
                        sdb.commit();
                    }
                    ctx.json(mu);
                }
                catch (Exception e) {
                    e.printStackTrace(System.out);
                    S_ABORT(ctx, sdb, "GET /hist/snapshot: Error in searching photos: "+e.getMessage(), 500, 
                        "Exception - check log"); 
                }
                finally { sdb.close(); }
            }                  
            
            catch(java.lang.NumberFormatException e) {  
                ABORT(ctx, db, "GET /hist/snapshot: Cannot parse number", 400,  "Cannot parse number");
            }
            catch(DateTimeParseException e) { 
                ABORT(ctx, db, "GET /hist/snapshot: Cannot parse date/time:"+e.getMessage(), 400, 
                  "Cannot parse date/time"); 
            } 
            catch(java.sql.SQLException e) { 
                ABORT(ctx, db, "GET /hist/snapshot: SQL error: "+e.getMessage(), 500, 
                    "SQL Error"); 
            }            
            catch(Exception e) { 
                e.printStackTrace(System.out);
                ABORT(ctx, db, "GET /hist/snapshot: Error: "+e.getMessage(), 500, 
                    "Exception - check log"); 
            }
            finally { db.close(); }
        });
        
        
        
        
     
        /************************************************************************* 
         * REST service:  
         * /hist/<callsign>/hrdvia?tfrom=...&tto=...   
         * Get heard points for a given callsign. 
         * Timespan is given as request parameters tfrom and tto 
         *************************************************************************/
         
        a.get("/hist/{src}/hrdvia", (ctx) -> {
            var src = ctx.pathParam("src").toUpperCase();
            MyDBSession db = _dbp.getDB();
            JsOverlay mu = null;
            
            try {
                String tfrom = ctx.queryParam("tfrom");
                String tto = ctx.queryParam("tto");
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
                ctx.json(mu);
            }
            catch(DateTimeParseException e) { 
                ABORT(ctx, db, "GET /hist/*/hrdvia: Cannot parse date/time:"+e.getMessage(), 400, 
                  "Cannot parse date/time"); 
            }  
            catch(java.sql.SQLException e) { 
                ABORT(ctx, db, "GET /hist/*/hrdvia: SQL error:"+e.getMessage(), 500, "SQL error (see log)"); 
            }  
            catch(Exception e) { 
                e.printStackTrace(System.out);
                ABORT(ctx, db, "GET /hist/*/hrdvia: Error:"+e.getMessage(), 500, "Error: "+e.getMessage()); 
            }      
            finally { db.close(); }
        } );       
    
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
                RuleSet vfilt, double scale, boolean reset) 
    {
        if (tp != null) {
            Action action = vfilt.apply(tp, (long) scale); 
            if (!action.hideAll()) {
                JsPoint p = createPoint(tp, trail.size() > 1, action);        
                p.trail = createTrail(tp, trail, action, reset);
                mu.points.add(p);
            }
        }
    }
   
   
   
    private void processPhoto(JsOverlay mu, Signs.Item p) 
    {
        if (p != null) {
            LatLng ref = p.getPosition();
            if (ref == null) 
                return;
            JsPoint x  = new JsPoint();
            x.ident  = p.getIdent();
            x.type   = p.getType();
            x.title = p.getDescr() == null ? "" : fixText(p.getDescr());
            x.href  = p.getUrl() == null ? "" : p.getUrl();
            x.pos    = new double[] {ref.getLng(), ref.getLat()};
            x.icon   = "/icons/"+ p.getIcon(); 
            mu.points.add(x);
        }
    }
   
   
   
    /** 
     * Convert Tracker point to JSON point. 
     * Return null if point has no position.  
     */
    private JsPoint createPoint(TrackerPoint s, boolean moving, Action action) {
        LatLng ref = s.getPosition();
        if (ref == null) 
            return null;
          
        JsPoint x  = new JsPoint();
        x.ident  = s.getDisplayId();
        x.label  = createLabel(s, moving, action);
        x.pos    = new double[] {ref.getLng(), ref.getLat()};
        
        String icon = s.getIcon(true); 
        if (icon==null)
            icon = _defaultIcon;
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
