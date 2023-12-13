
package no.polaric.aprsdb;
import no.polaric.aprsdb.http.*;
import no.polaric.aprsdb.dbsync.*;
import no.polaric.aprsd.*;
import no.polaric.aprsd.http.*;
import java.util.*;
import java.util.function.*;
import java.sql.*;
import javax.sql.*;
import net.postgis.jdbc.PGgeometry;
import net.postgis.jdbc.geometry.Point;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;


public class DatabasePlugin implements PluginManager.Plugin,  ReportHandler, StationDB.Hist, PluginApi
{
     protected DataSource _dsrc;
     private ServerAPI _api; 
     private DbMaintenance _maint; 
     private String _filter_chan, _filter1_chan, _filter2_chan;
     private String _filter_src, _filter1_src, _filter2_src;
     private String _filter_tag, _filter1_tag, _filter2_tag;
     private boolean _isActive = false;
     private boolean _isOwner = false; 
     private boolean _enableHist = false;
     private Logfile _log;
     private String  _dburl;
     private DbSync  _dbsync;



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
            config.setMaximumPoolSize(16);
            config.setAutoCommit(false);
            config.addDataSourceProperty("dataSourceClassName","org.postgresql.ds.PGSimpleDataSource");
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "1500");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "3000");
            config.addDataSourceProperty("gssEncMode", "disable");
            config.setConnectionTimeout(1000);
            _dsrc = new HikariDataSource(config);
           
           _filter_chan = api.getProperty("db.filter.chan", "");
           _filter_src = api.getProperty("db.filter.src", "");
             
           _filter1_chan = api.getProperty("db.filter1.chan", "");
           _filter1_src = api.getProperty("db.filter1.src", "");

           _filter2_chan = api.getProperty("db.filter2.chan", "");
           _filter2_src = api.getProperty("db.filter2.src", "");
           
           _enableHist = api.getBoolProperty("db.hist.on", true);
           boolean signs = api.getBoolProperty("db.signs.on", true);  
           boolean xdb = api.getBoolProperty("db.xqueries", true);
           api.properties().put("aprsdb.plugin", this); 
           
           _isOwner = api.getBoolProperty("db.isowner", true);
           _log = new Logfile(api, "db", "database.log");
           _api.log().info("DatabasePlugin", "Activate...");
           
           
           /*
            * Statistics 
            */
            DbStatLogger stats = new DbStatLogger(_api);
            
           
           /*
            * Synchronisation 
            */
           _dbsync = new DbSync(_api); 
           
           /* Add handlers */
           _dbsync.addCid("signs",   (Sync.Handler) new SignsSync(api, this)); 
           _dbsync.addCid("user",    (Sync.Handler) new UserSync(api, this));
           _dbsync.addCid("userts",  (Sync.Handler) new UserTsSync(api, this));
           _dbsync.addCid("object",  (Sync.Handler) new ObjectSync(api, this));
           _dbsync.addCid("objshare",(Sync.Handler) new ObjectShareSync(api, this));
           
           /* Activate and register user sync client */
           UserDb udb = (UserDb) _api.getWebserver().getUserDb(); 
           udb.setSyncer( new ClientUserSyncer(_dbsync) );
           

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
              
            
            /* Set stationDB implementation */
            StationDB dbi = api.getDB();
            ((StationDBImp) dbi).setHistDB(this);
           
           
            if (signs)                                   
              /* 
               * Create a anonymous class to offer search and close methods. 
               * FIXME: Consider to make a superclass to represent this pattern. Transaction?
               */
              Signs.setExtDb(new Signs.ExtDb() {
                  SignsDBSession db = null;
           
                  public Iterable<Signs.Item> search
                         (String uid, long scale, LatLng uleft, LatLng lright) {

                     try {                     
                        db = new SignsDBSession(getDB());
                        DbList<Signs.Item> x = db.getSigns(scale, uleft, lright);
                        DbList<Signs.Item> y = db.getPhotos(uid, null, uleft, lright);
                        x.merge(y);
                        x.reset();
                        
                        db.commit();
                        if (x==null)
                          /* If not found, return empty list */
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
              if (xdb)
                 AuthInfo.addService("xdatabase");
                
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
      
      
      
         
     @Override
     public void startWebservice(ServerAPI api) {
        RestApi api1     = new RestApi(api);
        api1.start();
        TrackerApi api2  = new TrackerApi(api);
        api2.start();
        HistApi api3     = new HistApi(api);
        api3.start();
        SignsApi api4    = new SignsApi(api);
        api4.start();
        TrackLogApi api5 = new TrackLogApi(api);
        api5.start();   
        PhotoApi api6    = new PhotoApi(api);
        api6.start();
        _dbsync.startRestApi();
     }
     
     
      
      
     /**  Stop the plugin */ 
      // FIXME
      public void deActivate() 
      {         
         boolean active = _api.getBoolProperty("db.plugin.on", false);
         if (!active)
            return; 
         
         _api.log().info("DatabasePlugin", "Deactivate");
         if (_log != null)
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

        
      public Sync getSync() 
        { return _dbsync; }
        
        
      public MyDBSession getDB() throws DBSession.SessionError
        { return getDB(false); }
        
      public MyDBSession getDB(boolean autocommit) throws DBSession.SessionError
         { return new MyDBSession(_dsrc, _api, autocommit, _log); }
      
      
      
      
      /**
       * Encode and add a position to a PostGIS SQL statement.
       * FIXME: THIS IS DUPLICATED IN MyDBSession 
       */
      private void setRef(PreparedStatement stmt, int index, LatLng pos)
         throws SQLException
      {
         Point p = new Point( pos.getLng(), pos.getLat() );
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
              
    
     /* Accept packet or posreport for storage */
     private boolean isAccepted(Source chan, String sender) {
        if (chan != null && chan.getIdent().matches(_filter_chan)  && sender != null && sender.matches(_filter_src))
            return true;
        if (chan != null && chan.getIdent().matches(_filter1_chan) && sender != null && sender.matches(_filter1_src))
            return true;
        if (chan != null && chan.getIdent().matches(_filter2_chan) && sender != null && sender.matches(_filter2_src))
            return true;
            
        return false;
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
       if (!isAccepted(chan, sender))
            return;
            
       MyDBSession db = null;   
       try {
           db = getDB();
           /* If change to comment, save it in database. */
           AprsPoint x = (AprsPoint) _api.getDB().getItem(sender, null);
           String comment = "";
           if ( x == null ||
               (descr != null && !descr.equals("") && !descr.equals(x.getDescr()))) {
               comment = "'"+descr+"'"; 
               comment = comment.replaceAll("\u0000", "");
           }
           else if (x.getLastChanged() != null)  {
               comment = "NULL"; 
              /* 
               * If item has not changed its position and the time since last 
               * update is less than 1 hour, return. Important: We assume that this 
               * method is called AFTER the realtime AprsPoint object is updated. 
               */
               // FIXME: is x.getLastChanged null in some cases? Just check for it? 
               boolean recentUpdate = (new java.util.Date()).getTime() < x.getLastChanged().getTime() + 1000*60*60*1; 
               if (!x.isChanging() && recentUpdate) 
                   return;
               if (!recentUpdate)
                   x.setChanging();
           }

               
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
       if (!isAccepted(p.source, p.from))
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
     * Save item info to Tracker table in database. 
     * Called from StationDBImp.
     * @param tp Item to update
     */
    public void saveManagedItem(TrackerPoint tp) 
    {
        try {
            getDB().simpleTrans("saveItem", x -> {
                TrackerDBSession ses = new TrackerDBSession(x);
                Tracker t = ses.getTracker(tp.getIdent());   
                String icon = (tp.iconOverride() ? tp.getIcon() : null);
                if (t==null)
                    ses.addTracker(tp.getIdent(), tp.getUser(), tp.getAlias(), icon);
                else
                    ses.updateTracker(tp.getIdent(), null, tp.getAlias(), icon);
                return null;
            });
        }
        catch (DBSession.SessionError e) { }
    } 
    
    
     
    /**
     * Update item from database if it is managed.
     * Called from StationDBImp. 
     *   in getItem() - when specifically requested for realtime items.
     *   in addPoint() - called from newStation() - when packet is received and RT item is not active. 
     * @param tp Item to update
     */
    public void updateManagedItem(TrackerPoint tp) 
    {
        try {
            getDB().simpleTrans("updateItem", x -> {
                /* 
                 * Get managed-tracker info from database.
                 * If found, use this info to update item. Set MANAGED tag. 
                 */
                TrackerDBSession ses = new TrackerDBSession(x);
                Tracker t = ses.getTracker(tp.getIdent());
                if (t == null || tp.hasTag("MANAGED") || tp.hasTag("RMAN")) 
                    return null; 
                     
                tp.setTag("MANAGED");
              //  tp.setPersistent(true);
                if (tp.getUser() == null)
                    tp.setUser(t.info.user);
                saveManagedItem(tp);
           
                /* Set alias and/or icon based on info from database */
                if (t.info.alias != null)
                    tp.setAlias(t.info.alias);
                if (t.info.icon != null)
                    tp.setIcon(t.info.icon);
                    
                /* if alias/icon set send RMAN message */
                if (_api.getRemoteCtl() != null && (t.info.alias != null || t.info.icon!=null)) {
                    tp.setTag("_srman");
                    _api.getRemoteCtl().sendRequestAll("RMAN", tp.getIdent()+" "+t.info.alias+" "+t.info.icon, null);
                }
                
                /* Get tags from database */
                ses.getTrackerTags(tp.getIdent())
                    .forEach( tt -> tp.setTag(tt) );
                return null;
            });
        }
        catch (DBSession.SessionError e) { }
    }
        
    
    
    /**
     * Remove item from someone's myTrackers list
     * @param id identifier (callsign) of item to remove. 
     */
    public void removeManagedItem(String id)
    {
        var psub = (no.polaric.aprsd.http.PubSub) _api.getWebserver().getPubSub();
        try {
            getDB().simpleTrans("removeManagedItem", x -> {
                TrackerDBSession ses = new TrackerDBSession(x);
                Tracker t = ses.getTracker(id);
                ses.deleteTracker(id);
                                
                _api.getWebserver().notifyUser(t.info.user, 
                        new ServerAPI.Notification("system", "system", "Your Tracker '"+id+"' was removed", new java.util.Date(), 60));
                        
                psub.put("trackers:"+t.info.user, null);
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
    
        
        
    /**
     * Log setting of alias
     * @param src identifier
     * @param alias Alias is set now. null to delete it. 
     */
    public synchronized void setAlias(TrackerPoint tp, String alias)
    {
        if (! isAccepted(tp.getSource(), tp.getIdent()))
            return;
        _log.info(null, "Set alias '"+alias+ "' for '"+tp.getIdent()+"'");
        try {
            getDB().simpleTrans("setAlias", x->
                { ((MyDBSession)x).setAlias(tp.getIdent(), alias); return null;});
        }
        catch (DBSession.SessionError e) { }
    }
     
     
    /**
     * Log setting of icon
     * @param src identifier
     * @param alias Icon filename is set now. null to delete it. 
     */
    public synchronized void setIcon(TrackerPoint tp, String icon)
    {
        if (! isAccepted(tp.getSource(), tp.getIdent()))
            return;
        _log.info(null, "Set icon for '"+tp.getIdent()+"'");
        try {
            getDB().simpleTrans("setIcon", x->
                { ((MyDBSession)x).setIcon(tp.getIdent(), icon); return null;});
        }
        catch (DBSession.SessionError e) { }
    }
       
       
       
    /**
     * Set icon
     * @param src identifier
     * @param tag Icon 
     * @param delete false if tag is to be added, true if it is to be removed
     */
    public synchronized void setTag(PointObject tp, String tag, boolean delete) 
    {
        if (! isAccepted(tp.getSource(), tp.getIdent()))
            return;
        try {
            getDB().simpleTrans("setTag", x->
                { ((MyDBSession)x).setTag(tp.getIdent(), tag, delete); return null;});
        }
        catch (DBSession.SessionError e) { }
    }
         
}
 
