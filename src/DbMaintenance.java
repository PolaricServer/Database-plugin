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
import  java.sql.*;
import  javax.sql.*;
import  java.util.concurrent.locks.*; 
import  no.polaric.aprsd.*;
import no.arctic.core.*;


/**
 * Database transaction. 
 */
 
public class DbMaintenance implements Runnable
{
   
   private AprsServerConfig _conf; 
   private DataSource _dsrc; 
   private int _maxage_raw, _maxage_report, _maxage_limited, _maxage_limited_raw;
   private String _maxage_limited_filter;
   private Thread _thread;
   private Logfile _log; 
   
   
   DbMaintenance (DataSource dsrc, AprsServerConfig conf, Logfile log)
   { 
      _dsrc = dsrc;
      _conf = conf; 
      
      _log = log; 
      _maxage_raw = Integer.parseInt(conf.getProperty("db.maxage.raw", "30").trim()); 
      _maxage_report = Integer.parseInt(conf.getProperty("db.maxage.report", "90").trim());
      _maxage_limited = Integer.parseInt(conf.getProperty("db.maxage2.report", "30").trim());
      _maxage_limited_raw = Integer.parseInt(conf.getProperty("db.maxage2.raw", "14").trim());
      _maxage_limited_filter = conf.getProperty("db.maxage2.filter", "src ~ '^LD.*'");
      _thread = new Thread(this, "db_maintenance");
      _thread.start();
   }
   
   public MyDBSession getDB() throws DBSession.SessionError
     { return new MyDBSession(_dsrc, _conf, false, _log); }
         
         
         
   public synchronized void deleteOldData( )
    {
        DBSession db = null;
        try {       
            db = getDB();
    
            /* Delete old data */
            PreparedStatement stmt = db.getCon().prepareStatement
              ( "DELETE FROM \"AprsPacket\" " + 
                "WHERE time + INTERVAL '"+_maxage_raw+" days' < 'now' OR" +
                     " (time + INTERVAL '"+_maxage_limited_raw+" days' < 'now' AND ("+_maxage_limited_filter+"))" );
            long deleted = stmt.executeUpdate();
            if (deleted > 0) 
              _log.info("DbMaintenance", "Deleted "+deleted+" old records from AprsPacket table"); 
           
            stmt = db.getCon().prepareStatement
              ( "DELETE FROM \"PosReport\" " + 
                "WHERE (time + INTERVAL '"+_maxage_report+" days' < 'now') OR"+ 
                     " (time + INTERVAL '"+_maxage_limited+" days' < 'now' AND ("+_maxage_limited_filter+"))" );
            deleted =  stmt.executeUpdate();
            if (deleted > 0) 
               _log.info("DbMaintenance","Deleted "+deleted+" old records from AprsMesssage table");
           
            /* Also delete data where time is in the future (because of bugs) */
            db.getCon().prepareStatement
              ( "DELETE FROM \"PosReport\" " + 
                "WHERE time > now() + INTERVAL '2 hours'" );
            deleted = stmt.executeUpdate();
            if (deleted > 0) 
               _log.info("DbMaintenance", "Deleted "+deleted+" records from AprsMesssage table with timestamps in future");
               
            /* Serverstats is kept for 5 years */
            db.getCon().prepareStatement
              ( "DELETE FROM \"ServerStats\" " + 
                "WHERE time + INTERVAL '5 years' < 'now'" );
            deleted = stmt.executeUpdate();
            if (deleted > 0) 
               _log.info("DbMaintenance", "Deleted "+deleted+" records from ServerStats table");
               
               
            db.getCon().prepareStatement
              ( "DELETE FROM \"DbSync\" " + 
                "WHERE ts + INTERVAL '2 years' < 'now'" );
            deleted = stmt.executeUpdate();
            if (deleted > 0) 
               _log.info("DbMaintenance", "Deleted "+deleted+" records from DbSync table");
               
               
            db.getCon().prepareStatement
              ( "DELETE FROM \"DbSync\" " + 
                "WHERE stable = 'true' AND ts + INTERVAL '3 months' < 'now'" );
            deleted = stmt.executeUpdate();
            if (deleted > 0) 
               _log.info("DbMaintenance", "Deleted "+deleted+" stable records from DbSync table");
               
            db.commit();
                
            db.getCon().prepareStatement
              ( "VACUUM ANALYZE \"AprsPacket\" " );
            stmt.executeUpdate();
            
            db.getCon().prepareStatement
              ( "VACUUM ANALYZE \"PosReport\" " );
            stmt.executeUpdate();
            
           db.commit();
       }
       catch (DBSession.SessionError e) {
           _log.error("DbMaintenance", "Cannot open database session: "+ e.getMessage());
       }
       catch (Exception e)
       {
           _log.warn("DbMaintenance", "deleteOldData: "+e);  
           if (db!=null) 
               db.abort();
       }
       finally { if (db !=null) db.close(); }
    }
   
   
   
   
   public void run()
   {   
        long period = 1000 * 60 * 60 * 4;     // 4 hours
        try { Thread.sleep(1000*15); } catch (Exception e) {} 
        _log.debug("DbMaintenance", "Starting database maintenance task...");
        try { Thread.sleep(1000*60*5); } catch (Exception e) {} 
        while(true) {
            _log.debug("DbMaintenance", "Starting database maintenance...");
            deleteOldData();  
            try { Thread.sleep(period); } catch (Exception e) {} 
        }  

   }    
     
}

