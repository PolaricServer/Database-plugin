
package no.polaric.aprsdb;
import no.polaric.aprsdb.http.*;
import no.polaric.aprsd.*;
import no.polaric.aprsd.http.*;
import java.util.*;
import java.util.function.*;
import uk.me.jstott.jcoord.*;
import java.sql.*;
import javax.sql.*;
import org.postgis.PGgeometry;
    
     import com.zaxxer.hikari.HikariConfig;
     import com.zaxxer.hikari.HikariDataSource;


public class DatabasePlugin implements PluginManager.Plugin,  AprsHandler, StationDB.Hist, PluginApi
{
     protected DataSource _dsrc;
     private ServerAPI _api; 
     private DbMaintenance _maint; 
     private String _filter_chan;
     private String _filter_src;
     private boolean _isActive = false;
     private boolean _isOwner = false; 
     private boolean _enableHist = false;
     private Logfile _log;
     private String  _dburl;



     /** Start the plugin  */
      public void activate(ServerAPI api)
      {
         _api = api;
         try {
           boolean active = api.getBoolProperty("db.plugin.on", false);
           if (!active)
               return; 
               
            /* Give PostgreSQL some time to start */
            try { Thread.sleep(1000*15); } catch (Exception e) {} 
           
            /*
             * Data source and pooling
             */
            HikariConfig config = new HikariConfig();
                      
            _dburl = api.getConfig().getProperty("db.url");
            config.setJdbcUrl( _dburl );
            config.setUsername( api.getConfig().getProperty("db.login")  );
            config.setPassword( api.getConfig().getProperty("db.passwd") );
            config.setMaximumPoolSize(10);
            config.setAutoCommit(false);
            config.addDataSourceProperty("dataSourceClassName","org.postgresql.ds.PGSimpleDataSource");
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "1000");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.setConnectionTimeout(1000);
            _dsrc = new HikariDataSource(config);
           
           
           _filter_chan = api.getProperty("db.filter.chan", ".*");
           _filter_src = api.getProperty("db.filter.src", ".*");
           _enableHist = api.getBoolProperty("db.hist.on", true);
           boolean signs = api.getBoolProperty("db.signs.on", true);  
           api.properties().put("aprsdb.plugin", this); 
           
           api.addHttpHandlerCls("no.polaric.aprsdb.http.Webserver", null);
           _isOwner = api.getBoolProperty("db.isowner", true);
           _log = new Logfile(api, "db", "database.log");
           _api.log().info("DatabasePlugin", "Activate...");
           
           /*
            * Start REST API.
            */
            
            RestApi api1 = new RestApi(api);
            api1.start();
            HistApi api2 = new HistApi(api);
            api2.start();
            GpxFileApi api3 = new GpxFileApi(api);
            api3.start();
            SignsApi api4 = new SignsApi(api);
            api4.start();
            
            
           /*
            * Writing spatiotemporal APRS data to db and maintenance operations shouldn't be 
            * done by more than one concurrent client (the owner of the database). 
            */
           if (_isOwner) {
              _api.getAprsParser().subscribe(this);
              _maint = new DbMaintenance(_dsrc, _api, _log);
           }
           else 
              _api.log().info("DatabasePlugin", "Using remote database server");
              
           StationDBImp dbi = (StationDBImp) api.getDB();
           dbi.setHistDB(this);
           
           
           
           if (signs)                                   
           
              /* 
               * Create a anonymous class to offer search and close methods. 
               * FIXME: Consider to make a superclass to represent this pattern. Transaction?
               */
              Signs.setExtDb(new Signs.ExtDb() {
                  MyDBSession db = null; 
           
                  public Iterable<Signs.Item> search
                         (long scale, Reference uleft, Reference lright) {

                     try {                     
                        db = getDB();
                        Iterable<Signs.Item> x = db.getSigns(scale, uleft, lright);
                        db.commit();
                        if (x==null)
                          return new ArrayList<Signs.Item>(1); 
                        return x; 
                     }
                     catch (DBSession.SessionError e) {
                        _api.log().error("DatabasePlugin", "Cannot open database: "+e.getMessage());
                        return new ArrayList<Signs.Item>(1);
                     }
                     catch (Exception e) { 
                        _api.log().warn(null, "Sign search: "+e); 
                        if (db != null) 
                            db.abort(); 
                        return new ArrayList<Signs.Item>(1);
                     }
                  }
                  
                  public void close() {
                    { if (db != null) db.close(); db=null; } 
                 }
                 
              });
              _isActive = true;
              AuthInfo.addService("database");
              _log.info(null, "DatabasePlugin activated");
        }
        catch (ClassCastException e) {
            _api.log().error("DatabasePlugin", "Cannot activate DatabasePlugin: unsupported impl. of StationDB");
        }
        catch (Exception e) {
            _api.log().error("DatabasePlugin", "Activate DatabasePlugin: "+e);
            e.printStackTrace(System.out);
        }  
      }
      
    
      
      
     /**  Stop the plugin */ 
      // FIXME
      public void deActivate() 
      {
         _api.log().info("DatabasePlugin", "Deactivate");
         _log.info(null, "DatabasePlugin deactivated");
         if (_isOwner)
            _api.getAprsParser().subscribe(this);
      }
      
      
      // FIXME
      public boolean isActive()
       { return true; }

       
      public Logfile log() 
       { return _log; }
       
       
      private String[] _dep = {};
      
      
      
     /** Return an array of other component (class names) this plugin depends on */
      public String[] getDependencies() { return _dep; }
      
      
      public String getDescr() {
         if (_dsrc == null)
            return "DatabasePlugin (deactivated)";
         String u = _dburl;
         u = u.replaceFirst("jdbc:postgresql://", "");
         return "DatabasePlugin ("+u+")"; 
      }

        
      public MyDBSession getDB() throws DBSession.SessionError
        { return getDB(false); }
        
      public MyDBSession getDB(boolean autocommit) throws DBSession.SessionError
         { return new MyDBSession(_dsrc, _api, autocommit, _log); }
      
      
      
      
      /**
       * Encode and add a position to a PostGIS SQL statement.
       * FIXME: THIS IS DUPLICATED IN MyDBSession 
       */
      private void setRef(PreparedStatement stmt, int index, Reference pos)
         throws SQLException
      {
         LatLng ll = pos.toLatLng();
         org.postgis.Point p = new org.postgis.Point( ll.getLng(), ll.getLat() );
         p.setSrid(4326);
         stmt.setObject(index, new PGgeometry(p));
      }
      
      
      
      
     /**
      * Encode a single character for use in a SQL query
      */
     private String qChar(char x)
     {
        if (x=='\'') return "'\'\''"; 
        else return "'" + x + "'";
     }
              

         
      
     /**
      * Log APRS position report to database.
      * FIXME: We should consider making this an asynchronous event handler
      */
     public synchronized void handlePosReport(Source chan, String sender, java.util.Date ts, PosData pd,
            String descr, String pathinfo)
     {
       if (!_enableHist)
          return;
       if (!chan.getIdent().matches(_filter_chan) || !sender.matches(_filter_src))
          return; 
       MyDBSession db = null;   
       try {
           db = getDB();
           /* If change to comment, save it in database. */
           AprsPoint x = (AprsPoint) _api.getDB().getItem(sender, null);
           String comment;
           if ( x == null ||
               (descr != null && !descr.equals("") && !descr.equals(x.getDescr()))) 
               comment = "'"+descr+"'";
           else {
               comment = "NULL"; 
              /* 
               * If item has not changed its position and the time since last 
               * update is less than 3 hours, return. Important: We assume that this 
               * method is called AFTER the in-memory AprsPoint object is updated. 
               */
               boolean recentUpdate = (new java.util.Date()).getTime() < x.getLastChanged().getTime() + 1000*60*60*3; 
               if (!x.isChanging() && recentUpdate) 
                   return;
               if (!recentUpdate)
                   x.setChanging();
           }
           comment = comment.replaceAll("\u0000", "");

               
           PreparedStatement stmt = db.getCon().prepareStatement
             ( "INSERT INTO \"PosReport\" (channel, src, time, rtime, speed, course, position, symbol, symtab, comment, nopkt)" + 
               " VALUES (?, ?, ?, ?, ?, ?, ?, "+qChar(pd.symbol)+", "+qChar(pd.symtab)+", ?, ?)" );
           stmt.setString(1, chan.getIdent());
           stmt.setString(2, sender);
           java.util.Date now = new java.util.Date();
           if (ts==null)
              ts = now;
           stmt.setTimestamp(3, DBSession.date2ts(ts));
           stmt.setTimestamp(4, DBSession.date2ts(now));
           stmt.setInt(5, pd.speed);
           stmt.setInt(6, pd.course);
           setRef(stmt, 7, pd.pos);      
           stmt.setString(8, comment);
           stmt.setBoolean(9, ("(EXT)".equals(pathinfo.toUpperCase())));
           stmt.executeUpdate(); 
           db.commit();
        }
        catch (DBSession.SessionError e) { 
            _api.log().error("DatabasePlugin", "handlePosReport: "+e);
        }
        catch (NullPointerException e) {
            _api.log().error("DatabasePlugin", "handlePosReport: "+e);
            e.printStackTrace(System.out);
            db.abort();
        }
        catch (Exception e) {
            _log.warn("DatabasePlugin", "handlePosReport: "+e);  
            e.printStackTrace(System.out);
            db.abort();
        }
        finally { 
            if (db!=null)
                db.close(); 
        }
     }
     
     
     
     /**
      * Log APRS status report to database. 
      */
     public void handleStatus(Source chan, java.util.Date ts, String msg) 
      { /* TBD */ }
    
    
                                   
     public void handleMessage(Source chan, java.util.Date ts, String src, String dest, String msg)
      { /* TBD */ }
    
    
    
     /**
      * Log APRS packet to database.
      */
     public synchronized void handlePacket(AprsPacket p)
     {
       if (!_enableHist)
          return;
       if (!p.source.getIdent().matches(_filter_chan) || !p.from.matches(_filter_src)) 
          return;
       
       MyDBSession db = null;
       String path = p.via;
       String[] pp = path.split(",q",2);
       String ipath = null;
       if (pp.length > 1) {
           ipath = "q"+pp[1];
           path = pp[0];
       }
       else if (path.matches("q..,.*")) {
           ipath = path; 
           path = null;
       }
        
       try {
           db = getDB();
           PreparedStatement stmt = db.getCon().prepareStatement
             ( "INSERT INTO \"AprsPacket\" (channel, src, dest, time, path, ipath, info)" + 
               "VALUES (?, ?, ?, ?, ?, ?,  ?)" );
            
           String rep = p.report.replaceAll("\0", "(NUL)");
           stmt.setString(1, p.source.getIdent());
           stmt.setString(2, p.from);
           stmt.setString(3, p.to);
           stmt.setTimestamp(4, DBSession.date2ts(p.time));
           stmt.setString(5, path);
           stmt.setString(6, ipath);
           stmt.setString(7, rep); 
           stmt.executeUpdate();
           db.commit();
       }
       catch (DBSession.SessionError e) {
            _api.log().error("DatabasePlugin", "handlePacket: "+e);
       }
       catch (NullPointerException e) {
           _api.log().error("DatabasePlugin", "handlePacket: "+e);
           e.printStackTrace(System.out);
           db.abort();
       }
       catch (Exception e) {
           _log.warn("DatabasePlugin", "handlePacket: "+e);  
           db.abort();
       }
       finally { 
          if (db!=null) 
             db.close(); 
       }
    }
     

    
    
    /**
     * Save item info to database. 
     * Called from StationDBImp.
     * @param tp Item to update
     */
    public void saveItem(TrackerPoint tp) 
    {
        try {
            getDB().simpleTrans("saveItem", x -> {
                MyDBSession ses = (MyDBSession) x;
                Tracker t = ses.getTracker(tp.getIdent());   
                String icon = (tp.iconOverride() ? tp.getIcon() : null);
                if (t==null)
                    ses.addTracker(tp.getIdent(), tp.getUser(), tp.getAlias(), icon);
                else
                    ses.updateTracker(tp.getIdent(), tp.getAlias(),icon);
                return null;
            });
        }
        catch (DBSession.SessionError e) { }
    } 
    
    
     
    /**
     * Update item from database.
     * Called from StationDBImp.
     * @param tp Item to update
     */
    public void updateItem(TrackerPoint tp) 
    {
        try {
            getDB().simpleTrans("updateItem", x -> {
                Tracker t = ((MyDBSession)x).getTracker(tp.getIdent());
                if (t != null) { 
                    tp.setTag("MANAGED");
                    tp.setPersistent(true, t.info.user, false); 
                    tp.setAlias(t.info.alias);
                    tp.setIcon(t.info.icon);
                }
                return null;
            });
        }
        catch (DBSession.SessionError e) { }
    }
        
        
        
    /**
     * Get an APRS item at a given point in time.
     */    
    public synchronized AprsPoint getItem(String src, java.util.Date at)
    {
        try {
            return (AprsPoint) getDB().simpleTrans("getItem", x->
                { return ((MyDBSession)x).getItem(src, at); });
        }
        catch (DBSession.SessionError e) { return null; }
    } 
     
     
     
    public synchronized Trail.Item getTrailPoint(String src, java.util.Date at)
    {
        try {
            return (Trail.Item) getDB().simpleTrans("getTrailPoint", x->
                { return ((MyDBSession)x).getTrailPoint(src, at); });
        }
        catch (DBSession.SessionError e) { return null; }
    }
    
    
}
 
