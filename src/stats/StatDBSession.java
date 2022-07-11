/* 
 * Copyright (C) 2022 by Øyvind Hanssen (ohanssen@acm.org)
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
 
public class StatDBSession extends DBSession
{
   
   private ServerAPI _api; 
   private DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
   
   
   
   StatDBSession (DataSource dsrc, ServerAPI api, boolean autocommit, Logfile log)
    throws DBSession.SessionError
   {
      super(dsrc, autocommit, log); 
      _api = api; 
   }
   
   
   StatDBSession(DBSession s) 
     throws DBSession.SessionError
   {
       super(s);
   }
   
   

    
    
    public void addStats(java.util.Date ts, int cli, int lgcli, long req, long vis, long logins, long pupd, long apupd, long mupd) 
                throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " INSERT INTO \"ServerStats\" "+
                " (time, nclients, nloggedin, httpreq, visits, logins, posupdates, aprsposupdates, mapupdates) " + 
                " VALUES (?,?,?,?,?,?,?,?,?)" );
        stmt.setTimestamp(1, new Timestamp(ts.getTime()));
        stmt.setInt(2, cli);
        stmt.setInt(3, lgcli);
        stmt.setLong(4, req);
        stmt.setLong(5, vis);
        stmt.setLong(6, logins);
        stmt.setLong(7, pupd);
        stmt.setLong(8, apupd);
        stmt.setLong(9, mupd);
        stmt.executeUpdate();
    }
    
    
    public void addStartup(java.util.Date ts) 
                throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " INSERT INTO \"ServerStart\" "+
                " (time) " + 
                " VALUES (?)" );
        stmt.setTimestamp(1, new Timestamp(ts.getTime()));
        stmt.executeUpdate();
    }

}

