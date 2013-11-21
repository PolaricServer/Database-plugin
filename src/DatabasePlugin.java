
package no.polaric.aprsdb;
import no.polaric.aprsd.*;
import no.polaric.aprsd.http.*;
import java.util.*;
import org.apache.commons.dbcp.*; 
import uk.me.jstott.jcoord.*;
import java.sql.*;
import javax.sql.*;
import org.postgis.PGgeometry;



public class DatabasePlugin implements PluginManager.Plugin,  AprsHandler, StationDB.Hist
{
     protected BasicDataSource _dsrc;
     private ServerAPI _api; 
     private DbMaintenance _maint; 
     private String _filter_chan;
     private String _filter_src;
     private boolean _isActive = false;
     
     
     /** Start the plugin  */
      public void activate(ServerAPI api)
      {
         try {
           System.out.println("*** DatabasePlugin.activate");
           Properties p = new Properties();
           p.put("accessToUnderlyingConnectionAllowed", "true");
           BasicDataSourceFactory dsf = new BasicDataSourceFactory();
           _dsrc = (BasicDataSource) dsf.createDataSource(p);
           _dsrc.setDriverClassName("org.postgresql.Driver");
           _dsrc.setUsername(api.getConfig().getProperty("db.login"));
           _dsrc.setPassword(api.getConfig().getProperty("db.passwd"));
           _dsrc.setUrl(api.getConfig().getProperty("db.url")); 
           _dsrc.setValidationQuery("select true");

           _filter_chan = api.getProperty("db.filter.chan", ".*");
           _filter_src = api.getProperty("db.filter.src", ".*");
           boolean signs = api.getBoolProperty("db.signs.on", true);  
           api.properties().put("aprsdb.plugin", this); 
           api.addHttpHandlerCls("no.polaric.aprsdb.XMLserver", null);
           api.addHttpHandlerCls("no.polaric.aprsdb.Webserver", null);
           boolean isowner = api.getBoolProperty("db.isowner", true);
           _api = api;
           
           /*
            * Writing spatiotemporal APRS data to db and maintenance operations shouldn't be 
            * done by more than one concurrent client (the owner of the database). 
            */
           if (isowner) {
              _api.setAprsHandler(this);
              _maint = new DbMaintenance(_dsrc, _api);
           }
           StationDBImp dbi = (StationDBImp) api.getDB();
           dbi.setHistDB(this);
           
           if (signs)                                   
           
              /* 
               * Create a anonymous class to offer search and close methods. 
               * FIXME: Consider to make a superclass to represent this pattern. Transaction?
               */
              Signs.setExtDb(new Signs.ExtDb() {
                  MyDBSession db = null; 
                  public Iterable<Signs.Item> search(long scale, UTMRef uleft, UTMRef lright) {
                     db = getDB();
                     try {
                        Iterable<Signs.Item> x = db.getSigns(scale, uleft, lright);
                        db.commit();
                        return x; 
                     }
                     catch (Exception e) 
                        { System.out.println("*** Sign search:"+e); db.abort(); return null;}
                  }
                  
                  public void close() {
                    { if (db != null) db.close(); db=null; } 
                 }    
              });
              _isActive = true;
        }
        catch (ClassCastException e) {
            System.out.println("*** Cannot activate DatabasePlugin: unsupported impl. of StationDB");
        }
        catch (Exception e) {
            System.out.println("*** Activate DatabasePlugin: "+e);}  
      }
      
      
      
      
      
      
      
      
      
      
     /**  Stop the plugin */ 
      // FIXME
      public void deActivate() 
      {
         System.out.println("*** DatabasePlugin.deactivate");
      }
      
      
      // FIXME
      public boolean isActive()
       { return true; }

       
       
       
       
      private String[] _dep = {};
      
      
      
     /** Return an array of other component (class names) this plugin depends on */
      public String[] getDependencies() { return _dep; }
      
      
      public String getDescr() {
         String u = _dsrc.getUrl();
         u = u.replaceFirst("jdbc:postgresql://", "");
         return "DatabasePlugin ("+u+")"; 
      }
      
      
      public MyDBSession getDB() 
        { return getDB(false); }
        
      public MyDBSession getDB(boolean autocommit)
         { return new MyDBSession(_dsrc, _api, autocommit); }
      
      
      
      
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
        if (x=='\'') return "'\\''"; 
        else return "'" + x + "'";
     }
              

         
      
     /**
      * Log APRS position report to database.
      */
     public synchronized void handlePosReport(Source chan, String sender, java.util.Date ts, PosData pd,
            String descr, String pathinfo)
     {
       if (!chan.getIdent().matches(_filter_chan) || !sender.matches(_filter_src))
          return; 
       DBSession db = getDB();   
       try {
           /* If change to comment, save it in database. */
           AprsPoint x = _api.getDB().getItem(sender, null);
           String comment;
           if ( x == null ||
               (descr != null && !descr.equals("") && !x.getDescr().equals(descr) ))
              comment = "'"+descr+"'";
           else {
              comment = "NULL"; 
              /* Add here: if position has not changed, return if time since previous save is less than a certain threshold.*/
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
       catch (Exception e)
       {
           System.out.println("*** WARNING (handlePosReport): "+e);  
           db.abort();
       }
       finally { db.close(); }
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
     public synchronized void handlePacket(Source chan, java.util.Date ts, String src, String dest, String path, String txt)
     {
       if (!chan.getIdent().matches(_filter_chan) || !src.matches(_filter_src))
          return;
       DBSession db = getDB();
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
           PreparedStatement stmt = db.getCon().prepareStatement
             ( "INSERT INTO \"AprsPacket\" (channel, src, dest, time, path, ipath, info)" + 
               "VALUES (?, ?, ?, ?, ?, ?,  ?)" );
             
           stmt.setString(1, chan.getIdent());
           stmt.setString(2, src);
           stmt.setString(3, dest);
           stmt.setTimestamp(4, DBSession.date2ts(ts));
           stmt.setString(5, path);
           stmt.setString(6, ipath);
           stmt.setString(7, txt); 
           stmt.executeUpdate();
           db.commit();
       }
       catch (Exception e) {
           System.out.println("*** WARNING (logAprsPacket): "+e);  
           db.abort();
       }
       finally { db.close(); }
    }
     

     
   /**
     * Get an APRS item at a given point in time.
     */    
    public synchronized AprsPoint getItem(String src, java.util.Date at)
    {
       MyDBSession db = getDB();
       try {
             AprsPoint x = db.getItem(src, at);
             db.commit();
             return x; 
       }
       catch (Exception e) {
           System.out.println("*** WARNING (getItem): "+e);  
           db.abort(); 
           return null;
       }   
       finally { db.close(); } 
    } 
     
     
     
    public synchronized Trail.Item getTrailPoint(String src, java.util.Date at)
    {
       MyDBSession db = getDB();
       try {
           Trail.Item x = db.getTrailPoint(src, at);
           db.commit();
           return x; 
       }
       catch (Exception e) {
           System.out.println("*** WARNING (getTrailPoint): "+e);  
           db.abort(); 
           return null;
       }   
       finally { db.close(); } 
    }
    
    
}
 
