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
 */

package no.polaric.aprsdb;
import  java.sql.*;
import  javax.sql.*;
import  java.util.*;
import  java.util.concurrent.locks.*; 
import  org.apache.commons.dbcp.*; 
import  no.polaric.aprsd.Logfile;


/* OBS: "lånt" fra CMSComp */

/**
 * Database connnection and (possibly) transaction. 
 * This class uses a pooling datasource to get/create a 
 * database connection when needed. It offers connections where
 * autocommit is off, and operations to commit or abort the 
 * transaction. 
 */
 
public class DBSession
{
     private Connection _con;
     private static Map<String, TransInfo> _inProgress = new HashMap();
     private static Timer _transTimer = new Timer("TransactionTimer");
     protected Logfile _log; 
     
     protected static class TransInfo extends TimerTask {
        public DBSession trans;
        public String key;
        
        public void run() {
           /* Abort and close the transaction */
            trans.abort();
            trans.close();
            trans._log.info(null, "DB Transaction '"+key+"' aborted. Timeout.");
           DBSession._inProgress.remove(key);
        }
        
        public TransInfo(String k, DBSession t) {
           key = k;
           trans = t;
        }
     }
     
     
     /**
      * Store ongoing transaction for later conclusion. 
      * If not restored after 5 minutes, it will be aborted and closed. 
      */
     public static void putTrans(String key, DBSession t)
     {
        TransInfo ti = new TransInfo(key, t);
        _inProgress.put(key, ti);
        _transTimer.schedule(ti, 1000 * 60 * 5);
     }
     
     
     /**
      * Get transaction for further execution and commit.
      */
     public static DBSession getTrans(String key)
     {
        TransInfo ti = _inProgress.get(key);
        ti.cancel();
        _inProgress.remove(key);
        return ti.trans;
     }
     
     
     
     
     
     
     /**
      * Constructor. 
      * @param dsrc JDBC DataSource object. 
      */ 
     public DBSession(DataSource dsrc, boolean autocommit, Logfile log)
     {
          try { 
            if (_con == null) { 
               _log = log;
               _con = dsrc.getConnection(); 
               _con.setAutoCommit(autocommit);

               /* PostGIS extensions */
               Connection dconn = ((DelegatingConnection) _con).getInnermostDelegate();
               ((org.postgresql.PGConnection)dconn).addDataType("geometry","org.postgis.PGgeometry");
               ((org.postgresql.PGConnection)dconn).addDataType("box3d","org.postgis.PGbox3d");
            }
         }
         catch (Exception e) {
             // FIXME: Re-throw exception here. 
             _log.warn("DbSession", "Cannot open db connection: "+e);
         }   
     }

                   
     protected static Timestamp date2ts(java.util.Date d, int offset)
       { return new Timestamp ( (long) ( (long) (d.getTime()+offset)/100)*100 ); }
       
     protected static Timestamp date2ts(java.util.Date d)
       { return date2ts(d, 0); }
       
       
     public Connection getCon() 
        { return _con; }
        
        
        
     /**
      * Returns true if transaction is open.
      */
     public boolean isOpen()
     {
         return (_con != null); 
     }
     
     
     
     /**
      * Commit a transaction if active. 
      */
     public synchronized void commit() throws SQLException
     {
         if (_con != null) {
             _con.commit();
         }
         else 
             _log.warn("DbSession", "Tried to commit a non-existing transaction");
     }
     
     
     
     /**
      * Abort a transaction if active.
      */
     public synchronized void abort()
     {
         try {
            if (_con != null) {
             _con.rollback();
           } 
           else
               _log.warn("DbSession", "Tried to abort a non-existing transaction");
         }
         catch (SQLException e) {}
     }


     
     /**
      * Close the connection, i.e. return it to the pool. 
      * Therefore it is important that this method is only invoked 
      * from within a <i>finally</i> block. 
      */
     public synchronized void close() 
     {
         try {
             if (_con != null)
             {
                 _con.setAutoCommit(true);
                 _con.close();
             }
             _con = null; 
         }
         catch (Exception e) {
            _log.warn("DbSession", "Try to close connection: "+e);
         }
         finally { 
         }   
     }
}

