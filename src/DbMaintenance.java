/* 
 * Copyright (C) 2014 by Øyvind Hanssen (ohanssen@acm.org)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 */

package no.polaric.aprsdb;
import  java.sql.*;
import  javax.sql.*;
import  java.util.concurrent.locks.*; 
import  uk.me.jstott.jcoord.*;
import  no.polaric.aprsd.*;



/**
 * Database transaction. 
 */
 
public class DbMaintenance implements Runnable
{
   
   private ServerAPI _api; 
   private DataSource _dsrc; 
   private int _maxage_raw, _maxage_report, _maxage_limited, _maxage_limited_raw;
   private String _maxage_limited_filter;
   private Thread _thread;
   private Logfile _log; 
   
   
   DbMaintenance (DataSource dsrc, ServerAPI api, Logfile log)
   { 
      _dsrc = dsrc;
      _api = api; 
      
      _log = log; 
      _maxage_raw = Integer.parseInt(api.getConfig().getProperty("db.maxage.raw", "30").trim()); 
      _maxage_report = Integer.parseInt(api.getConfig().getProperty("db.maxage.report", "90").trim());
      _maxage_limited = Integer.parseInt(api.getConfig().getProperty("db.maxage2.report", "30").trim());
      _maxage_limited_raw = Integer.parseInt(api.getConfig().getProperty("db.maxage2.raw", "14").trim());
      _maxage_limited_filter = api.getConfig().getProperty("db.maxage2.filter", "src ~ '^LD.*'");
      _thread = new Thread(this, "db_maintenance");
      _thread.start();
   }
   
   public MyDBSession getDB() throws DBSession.SessionError
     { return new MyDBSession(_dsrc, _api, false, _log); }
         
         
         
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
              ( "DELETE FROM \"AprsMessage\" " + 
                "WHERE (time + INTERVAL '"+_maxage_report+" days' < 'now') OR"+ 
                     " (time + INTERVAL '"+_maxage_limited+" days' < 'now' AND ("+_maxage_limited_filter+"))" );
           deleted =  stmt.executeUpdate();
           if (deleted > 0) 
               _log.info("DbMaintenance","Deleted "+deleted+" old records from AprsMesssage table");
           
           /* Also delete data where time is in the future (because of bugs) */
           db.getCon().prepareStatement
              ( "DELETE FROM \"AprsMessage\" " + 
                "WHERE time > 'now + INTERVAL 2 hours'" );
           deleted = stmt.executeUpdate();
           if (deleted > 0) 
               _log.info("DbMaintenance", "Deleted "+deleted+" records from AprsMesssage table with timestamps in future");
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

