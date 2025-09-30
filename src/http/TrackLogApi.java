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
import no.polaric.aprsd.point.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import no.polaric.aprsd.filter.*;
import  java.sql.*;
import  javax.sql.*;

 
public class TrackLogApi extends ServerBase implements JsonPoints
{
    private AprsServerConfig _api; 
    private PluginApi _dbp;
    private Map<String, Long> _tstamps = new HashMap<String, Long>();
    
    public java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
       
    public TrackLogApi(AprsServerConfig api) {
        super (api); 
        _api = api;
        _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
    }
        
        
        
    public static class LogItem implements Serializable {
        public long time; 
        public long lat, lng;        
        public LogItem() {};
    }
     
     
    public static class TrkLog implements Serializable {
        public String call;
        public LogItem[] pos;
        public TrkLog() {}
    }
    
    
    
    /** 
     * Return an error status message to client 
     */
    public Object ERROR(Context ctx, int status, String msg)
      { ctx.status(status); ctx.result(msg); return null;}
    
      
      
    public Object ABORT(Context ctx, MyDBSession db, String logmsg, int status, String msg) {
        _dbp.log().warn("TrackLogApi", logmsg);
        if (db!=null)
            db.abort();
        return ERROR(ctx, status, msg);
    }
    
    
    
    /**
     * Insert a pos report into the database
     */
    public void insertPosReport(MyDBSession db, String src, java.util.Date ts, int speed, int course, LatLng pos)
        throws DBSession.SessionError, SQLException 
    {

        PreparedStatement stmt = db.getCon().prepareStatement
            ( "INSERT INTO \"PosReport\" (channel, src, time, rtime, speed, course," + 
                " position, comment, nopkt)" + 
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)" );

        stmt.setString(1, "(int)");
        stmt.setString(2, src);
        java.util.Date now = new java.util.Date();
        stmt.setTimestamp(3, DBSession.date2ts(ts));
        stmt.setTimestamp(4, DBSession.date2ts(now));
        stmt.setInt(5, speed);
        stmt.setInt(6, course);
        DBSession.setRef(stmt, 7, pos);      
        stmt.setString(8, null);
        stmt.setBoolean(9, true);
        stmt.executeUpdate();  

        /* 
         * If report is recent, we also add it to real-time trail. 
         */
        TrackerPoint tp = _api.getDB().getItem(src, null);
        if (tp==null)
            return;
        Trail t = tp.getTrail();
        if (t != null && t.oldestPoint() != null && ts.getTime() > t.oldestPoint().getTime())
            t.add(ts, pos, speed, course, "(int)");
    }

    
    
    
    
    /** 
     * Set up the webservices. 
     */
    public void start() {   
        
        protect("/arctic/*");
    
    
        /******************************************
         * Echo service to test
         ******************************************/
        a.post("/arctic/echo", (ctx) -> {
            String body = ctx.body();
            ctx.result("Polaric Server: "+body);
        });
        
    
            
        /******************************************
         * POST of tracklogs
         ******************************************/
        a.post("/arctic/trklog", (ctx) -> {        
            TrkLog tr = (TrkLog) 
                ServerBase.fromJson(ctx.body(), TrkLog.class);
            if (tr==null) { 
                ERROR(ctx, 400, "Invalid message body");
                return;
            }
            
            MyDBSession db = _dbp.getDB();
            try {
                for (LogItem x : tr.pos) 
                    insertPosReport(db, tr.call, new java.util.Date(x.time*1000), -1, -1, 
                        new LatLng(((double) x.lat)/100000, ((double) x.lng)/100000));
                db.commit();
                ctx.result("Ok");
            }
            catch (java.sql.SQLException e) {
                ABORT(ctx, db, "POST /tracklog: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            catch (Exception e) {
                e.printStackTrace(System.out);
                ABORT(ctx, db, "POST /tracklog: Error:"+e.getMessage(),
                    500, "Error: "+e.getMessage());
            }
            finally { db.close(); }
        });
        
    }
}
