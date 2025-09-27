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
 
package no.polaric.aprsdb;
import no.arctic.core.*;
import no.arctic.core.auth.*;
import no.arctic.core.httpd.*;
import no.polaric.aprsdb.http.*;
import no.polaric.aprsdb.dbsync.*;
import no.polaric.aprsd.*;
import no.polaric.aprsd.point.*;
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
     private AprsServerConfig _conf; 
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
     private int _photoscale;



     /** Start the plugin  */
      public void activate(AprsServerConfig conf)
      {
         _conf = conf;
         try {
            boolean active = conf.getBoolProperty("db.plugin.on", false);
            if (!active)
               return; 
               
            /* Give PostgreSQL some time to start */
            try { Thread.sleep(1000*15); } catch (Exception e) {} 
           
            /*
             * Data source and pooling
             */
            HikariConfig config = new HikariConfig();
                      
            _dburl = conf.config().getProperty("db.url");
            config.setJdbcUrl( _dburl );
            config.setUsername( conf.config().getProperty("db.login")  );
            config.setPassword( conf.config().getProperty("db.passwd") );
            config.setMaximumPoolSize(64);
            config.setAutoCommit(false);
            config.addDataSourceProperty("dataSourceClassName","org.postgresql.ds.PGSimpleDataSource");
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "1500");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "3000");
            config.addDataSourceProperty("gssEncMode", "disable");
            config.setConnectionTimeout(5000);
            _dsrc = new HikariDataSource(config);
           
           _filter_chan = conf.getProperty("db.filter.chan", "");
           _filter_src = conf.getProperty("db.filter.src", "");
             
           _filter1_chan = conf.getProperty("db.filter1.chan", "");
           _filter1_src = conf.getProperty("db.filter1.src", "");

           _filter2_chan = conf.getProperty("db.filter2.chan", "");
           _filter2_src = conf.getProperty("db.filter2.src", "");
           
           _enableHist = conf.getBoolProperty("db.hist.on", true);
           boolean signs = conf.getBoolProperty("db.signs.on", true);  
           boolean xdb = conf.getBoolProperty("db.xqueries", true);
           _photoscale = conf.getIntProperty("db.photos.maxscale", 500000);
           conf.properties().put("aprsdb.plugin", this); 
           
           _isOwner = conf.getBoolProperty("db.isowner", true);
           _log = new Logfile(conf, "db", "database.log");
           _conf.log().info("DatabasePlugin", "Activate...");
           
           
           /*
            * Statistics 
            */
            DbStatLogger stats = new DbStatLogger(_conf);
                 

           /*
            * Writing spatiotemporal APRS data to db and maintenance operations shouldn't be 
            * done by more than one concurrent client (the owner of the database). 
            */
            if (_isOwner) {
               _conf.getAprsParser().subscribe(this);
               _maint = new DbMaintenance(_dsrc, _conf, _log);
            }
            else 
               _conf.log().info("DatabasePlugin", "Using remote database server");
              
            
            /* Set stationDB implementation */
            StationDB dbi = conf.getDB();
            ((StationDBImp) dbi).setHistDB(this);
           
           
            if (signs)                                   
              /* 
               * Create a anonymous class to offer search and close methods. 
               * FIXME: Consider to make a superclass to represent this pattern. Transaction?
               */
              Signs.setExtDb(new Signs.ExtDb() {
                  SignsDBSession db = null;
           
                  public Iterable<Signs.Item> search
                         (String uid, String group, long scale, LatLng uleft, LatLng lright) {

                     try {                     
                        db = new SignsDBSession(getDB());
                        DbList<Signs.Item> x = db.getSigns(scale, uleft, lright);
                        if (scale < _photoscale) {
                            DbList<Signs.Item> y = db.getPhotos(uid, group, null, uleft, lright);
                            x.merge(y);
                        }
                        x.reset();
                        
                        db.commit();
                        if (x==null)
                          /* If not found, return empty list */
                          return new ArrayList<Signs.Item>(1); 
                        return x; 
                     }
                     catch (DBSession.SessionError e) {
                        _conf.log().error("DatabasePlugin", "Cannot open database: "+e.getMessage());
                        return new ArrayList<Signs.Item>(1);
                     }
                     catch (Exception e) { 
                        _conf.log().warn(null, "Sign search: "+e); 
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
            _conf.log().error("DatabasePlugin", "Cannot activate DatabasePlugin: unsupported impl. of StationDB");
        }
        catch (Exception e) {
            _conf.log().error("DatabasePlugin", "Activate DatabasePlugin: "+e);
            e.printStackTrace(System.out);
        }  
      }
      
      
      
         
     @Override
     public void startWebservice(AprsServerConfig conf) {
        
        boolean active = conf.getBoolProperty("db.plugin.on", false);
            if (!active)
               return; 
        if (!_isActive)
            return; 
        RestApi api1     = new RestApi(conf);
        api1.start();
        TrackerApi api2  = new TrackerApi(conf);
        api2.start();
        HistApi api3     = new HistApi(conf);
        api3.start();
        SignsApi api4    = new SignsApi(conf);
        api4.start();
        TrackLogApi api5 = new TrackLogApi(conf);
        api5.start();   
        PhotoApi api6    = new PhotoApi(conf);
        api6.start();
        
        /*
         * Synchronisation 
         */
        _dbsync = new DbSync(_conf); 
           
        /* Add handlers */
        _dbsync.addCid("signs",   (Sync.Handler) new SignsSync(conf, this)); 
        _dbsync.addCid("user",    (Sync.Handler) new UserSync(conf, this));
        _dbsync.addCid("userts",  (Sync.Handler) new UserTsSync(conf, this));
        _dbsync.addCid("object",  (Sync.Handler) new ObjectSync(conf, this));
        _dbsync.addCid("objshare",(Sync.Handler) new ObjectShareSync(conf, this));
           
        /* Activate and register user sync client */
        UserDb udb = (UserDb) _conf.getWebserver().userDb(); 
        udb.setSyncer( new ClientUserSyncer(_conf, _dbsync) );
        
        _dbsync.startRestApi();
    }
     
     
      
      
     /**  Stop the plugin */ 
      // FIXME
      public void deActivate() 
      {         
         boolean active = _conf.getBoolProperty("db.plugin.on", false);
         if (!active)
            return; 
         
         _conf.log().info("DatabasePlugin", "Deactivate");
         if (_log != null)
            _log.info(null, "DatabasePlugin deactivated");
         if (_isOwner)
            _conf.getAprsParser().subscribe(this);
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
         { return new MyDBSession(_dsrc, _conf, autocommit, _log); }
      
      
      public int getPhotoScale() {
        return _photoscale;
      }
      
      
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
           AprsPoint x = (AprsPoint) _conf.getDB().getItem(sender, null);
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
            _conf.log().error("DatabasePlugin", "handlePosReport: "+e);
        }
        catch (NullPointerException e) {
            _conf.log().error("DatabasePlugin", "handlePosReport: "+e);
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
            _conf.log().error("DatabasePlugin", "handlePacket: "+e);
       }
       catch (NullPointerException e) {
           _conf.log().error("DatabasePlugin", "handlePacket: "+e);
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
                if (_conf.getRemoteCtl() != null && (t.info.alias != null || t.info.icon!=null)) {
                    tp.setTag("_srman");
                    _conf.getRemoteCtl().sendRequestAll("RMAN", tp.getIdent()+" "+t.info.alias+" "+t.info.icon, null);
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
        var psub = (PubSub) _conf.getWebserver().pubSub();
        try {
            getDB().simpleTrans("removeManagedItem", x -> {
                TrackerDBSession ses = new TrackerDBSession(x);
                Tracker t = ses.getTracker(id);
                ses.deleteTracker(id);
                                
                _conf.getWebserver().notifyUser(t.info.user, 
                        new ServerConfig.Notification("system", "system", "Your Tracker '"+id+"' was removed", new java.util.Date(), 60));
                        
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
 
