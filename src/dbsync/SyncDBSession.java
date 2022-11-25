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

package no.polaric.aprsdb.dbsync;
import  no.polaric.aprsdb.*;
import  no.polaric.aprsd.*;
import  java.text.*;
import  java.sql.*;
import  javax.sql.*;
import  java.util.concurrent.locks.*; 
import  uk.me.jstott.jcoord.*;
import  no.polaric.aprsd.*;
import  org.postgis.PGgeometry;
import  java.io.*;



/**
 * Database transaction. 
 */
 
public class SyncDBSession extends DBSession
{
   
   private ServerAPI _api; 
   private DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
   
   
   public static class SyncOp {
        Date ts; 
        String cmd;
        SyncOp(Date t, String o) {
            ts=t; cmd=o;
        }
   }
   
   
   SyncDBSession (DataSource dsrc, ServerAPI api, boolean autocommit, Logfile log)
    throws DBSession.SessionError
   {
      super(dsrc, autocommit, log); 
      _api = api; 
   }
   
   
   SyncDBSession(DBSession s) 
     throws DBSession.SessionError
   {
       super(s);
   }
   
   

    /* 
     * Get time when cid/item was last updated 
     */
    public SyncOp getSync(String cid, String item)
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT ts,op from \"DbSync\" "+
              " WHERE cid=? AND item=? " +
              " ORDER BY ts DESC ", 
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
              
        stmt.setString(1, cid);
        stmt.setString(2, item);
        ResultSet rs = stmt.executeQuery();
        if (rs.next())
            return new SyncOp(new Date(rs.getTimestamp("ts").getTime()), rs.getString("op")); ;
        return null;
    }
    
    
    
    /* 
     * Set last-update-time for cid/item .
     */
    public void setSync(String cid, String item, String cmd, java.util.Date ts)
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " UPDATE \"DbSync\"" + 
                " SET ts=?, op=? WHERE cid=? AND item=?" );
        stmt.setTimestamp(1, DBSession.date2ts(ts));
        stmt.setString(2, cmd);
        stmt.setString(3, cid);
        stmt.setString(4, item);
        int n = stmt.executeUpdate();
        
        if (n==0) {
            stmt = getCon().prepareStatement
              ( " INSERT INTO \"DbSync\" (cid,item,op,ts)" + 
                " VALUES (?, ?, ?, ?)" );
            stmt.setString(1, cid);
            stmt.setString(2, item);
            stmt.setString(3, cmd);
            stmt.setTimestamp(4, DBSession.date2ts(ts));
            stmt.executeUpdate();
        }
        
    }
    
    
    
    
    public DbList<Sync.ItemUpdate> getSyncUpdates(String peer) 
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT * from \"DbSyncQueue\" "+
              " WHERE peer=? order by ts asc",  
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, peer);

        return new DbList( stmt.executeQuery(), rs ->
            { return new Sync.ItemUpdate
               (rs.getString("cid"), rs.getString("item"), rs.getString("userid"), rs.getString("cmd"), rs.getString("arg"), 
               rs.getTimestamp("ts").getTime(), rs.getString("origin"));  });      
    }
    
    
    
    
    public void removeSyncUpdates(String peer, java.util.Date ts) 
                throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " DELETE FROM \"DbSyncQueue\"" + 
                " WHERE peer=? AND ts<=?" );
        stmt.setString(1, peer);
        stmt.setTimestamp(2, DBSession.date2ts(ts));
        stmt.executeUpdate();
    }
    
    
    
    
    public void addSyncUpdate(String peer, Sync.ItemUpdate upd) 
                throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " INSERT INTO \"DbSyncQueue\" (peer,cid,item,userid,ts,cmd,origin,arg) " + 
                " VALUES (?,?,?,?,?,?,?,?)" );
        stmt.setString(1, peer);
        stmt.setString(2, upd.cid);
        stmt.setString(3, upd.itemid);
        stmt.setString(4, upd.userid);
        stmt.setTimestamp(5, new Timestamp(upd.ts));
        stmt.setString(6, upd.cmd);
        stmt.setString(7, upd.origin);
        stmt.setString(8, upd.arg);
        stmt.executeUpdate();
    }
    

}

