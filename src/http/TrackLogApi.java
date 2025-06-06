
package no.polaric.aprsdb.http;
import no.polaric.aprsdb.*;
import no.polaric.aprsd.*;
import no.polaric.aprsd.http.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
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
import  java.sql.*;
import  javax.sql.*;

 
public class TrackLogApi extends ServerBase implements JsonPoints
{
    private ServerAPI _api; 
    private PluginApi _dbp;
    private Map<String, Long> _tstamps = new HashMap<String, Long>();
    
    public java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
       
    public TrackLogApi(ServerAPI api) {
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
    public String ERROR(Response resp, int status, String msg)
      { resp.status(status); return msg; }
      
      
      
    public String ABORT(Response resp, MyDBSession db, String logmsg, int status, String msg) {
        _dbp.log().warn("TrackLogApi", logmsg);
        db.abort();
        return ERROR(resp, status, msg);
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
        _api.getWebserver().protectDeviceUrl("/arctic/*");
    
    
    
    
        /******************************************
         * Echo service to test
         ******************************************/
        post("/arctic/echo", (req, resp) -> {
            String body = req.body();
            return "Polaric Server: "+body;
        });
        
    
            
        /******************************************
         * POST of tracklogs
         ******************************************/
        post("/arctic/trklog", (req, resp) -> {        
            TrkLog tr = (TrkLog) 
                ServerBase.fromJson(req.body(), TrkLog.class);
            if (tr==null)
                ERROR(resp, 400, "Invalid message body");
                
            MyDBSession db = _dbp.getDB();
            try {
                for (LogItem x : tr.pos) 
                    insertPosReport(db, tr.call, new java.util.Date(x.time*1000), -1, -1, 
                        new LatLng(((double) x.lat)/100000, ((double) x.lng)/100000));
                db.commit();
                return "Ok";
            }
            catch (java.sql.SQLException e) {
                return ABORT(resp, db, "POST /tracklog: SQL error:"+e.getMessage(),
                    500, "SQL error: "+e.getMessage());
            }
            catch (Exception e) {
                e.printStackTrace(System.out);
                return ABORT(resp, db, "POST /tracklog: Error:"+e.getMessage(),
                    500, "Error: "+e.getMessage());
            }
            finally { db.close(); }
        });
        
    }
}
