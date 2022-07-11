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
    public Date getSync(String cid, String item)
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT ts from \"DbSync\" "+
              " WHERE cid=? AND item=?",  
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, cid);
        stmt.setString(2, item);
        ResultSet rs = stmt.executeQuery();
        if (rs.next())
            return new Date(rs.getTimestamp("ts").getTime());
        return null;
    }
    
    
    
    /* 
     * Set last-update-time for cid/item .
     */
    public void setSync(String cid, String item, java.util.Date ts)
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " UPDATE \"DbSync\"" + 
                " SET ts=? WHERE cid=? AND item=?" );
        stmt.setTimestamp(1, DBSession.date2ts(ts));
        stmt.setString(2, cid);
        stmt.setString(3, item);
        int n = stmt.executeUpdate();
        
        if (n==0) {
            stmt = getCon().prepareStatement
              ( " INSERT INTO \"DbSync\" (cid,item,ts)" + 
                " VALUES (?, ?, ?)" );
            stmt.setString(1, cid);
            stmt.setString(2, item);
            stmt.setTimestamp(3, DBSession.date2ts(ts));
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
               rs.getTimestamp("ts").getTime());  });      
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
              ( " INSERT INTO \"DbSyncQueue\" (peer,cid,item,userid,ts,cmd,arg) " + 
                " VALUES (?,?,?,?,?,?,?)" );
        stmt.setString(1, peer);
        stmt.setString(2, upd.cid);
        stmt.setString(3, upd.itemid);
        stmt.setString(4, upd.userid);
        stmt.setTimestamp(5, new Timestamp(upd.ts));
        stmt.setString(6, upd.cmd);
        stmt.setString(7, upd.arg);
        stmt.executeUpdate();
    }
    

}

