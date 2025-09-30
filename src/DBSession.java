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
import  no.polaric.core.*;
import  no.polaric.aprsd.*;
import  no.polaric.aprsd.point.*;
import  java.sql.*;
import  javax.sql.*;
import  java.util.*;
import  java.util.function.*;
import  java.util.concurrent.locks.*; 
import  net.postgis.jdbc.PGgeometry;
import  net.postgis.jdbc.geometry.Point;



/**
 * Database connnection and (possibly) transaction. 
 * This class uses a pooling datasource to get/create a 
 * database connection when needed. It offers connections where
 * autocommit is off, and operations to commit or abort the 
 * transaction. 
 */
 
public class DBSession
{   
     protected AprsServerConfig _api; 
     private Connection _con;
     private static Map<String, TransInfo> _inProgress = new HashMap<String, TransInfo>();
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
     
     
     public static class SessionError extends Exception {
        SessionError(String msg, Throwable cause) {
            super(msg, cause);
        }
     }
     
          
     public interface Transaction {
         Object accept(DBSession t) throws SQLException;
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
     public DBSession(DataSource dsrc, boolean autocommit, Logfile log) throws SessionError
     {
          try { 
            if (_con == null) { 
               _log = log;
               _con = dsrc.getConnection(); 
               _con.setAutoCommit(autocommit);
            }
         }
         catch (Exception e) {
             _log.error("DbSession", "Cannot open database: "+e.getMessage());
             _con = null;
             throw new SessionError(e.getMessage(), e);
         }   
     }

     
     
     public DBSession(DBSession s) 
     {
        _log = s._log;
        _con = s._con; 
        _api = s._api;
     }
     
     
                   
     public static Timestamp date2ts(java.util.Date d, int offset)
       { return new Timestamp ( (long) ( (long) (d.getTime()+offset)    /100)*100 ); }
       
     public static Timestamp date2ts(java.util.Date d)
       { return date2ts(d, 0); }
    
    
     /**
       * Encode and add a position to a PostGIS SQL statement.
       */
     public static void setRef(PreparedStatement stmt, int index, LatLng pos)
       throws SQLException 
     {
        if (pos==null)
            stmt.setNull(index, java.sql.Types.NULL);
        else {
            Point p = new Point( pos.getLng(), pos.getLat() );
            p.setSrid(4326);
            stmt.setObject(index, new PGgeometry(p));
        }
     }
        
       
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
            _log.warn("DbSession", "Tried to close connection: "+e);
         }
         finally { 
         }   
     }
     


     public synchronized Object simpleTrans(String name, Transaction f)
     {
         try {
             Object ret = f.accept(this);
             commit();
             return ret;
         }
         catch (Exception e) {
            _log.warn("DbSession", "Aborted transaction, "+name+": "+e);  
            e.printStackTrace(System.out);
            abort(); 
            return null;
         }   
         finally { close(); } 
     }
     
     
}

