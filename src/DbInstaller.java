/* 
 * Copyright (C) 2013 by Oyvind Hanssen (ohanssen@acm.org)
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
import java.util.Hashtable;
import java.util.Enumeration;
import java.sql.*;
import java.util.*;
import java.io.*;


/**
 * This is the database schema installer
 */
public class DbInstaller 
{    
     private static String _url, _login, _passwd;
     private static Connection _db;

         
     static {
        // Load the JDBC driver
        try {
          Class.forName("org.postgresql.Driver");
        }
        catch (ClassNotFoundException e) {
            System.err.println("Board(static init): Couldn't load JDBC driver");
            System.exit(1);
        }
     }
     
     
     protected static void createClass(String name, String superclass, String attrs)
     {
        updateQuery(
           "CREATE TABLE \""+ name + "\" (" +attrs + ")" + 
           (superclass!=null ? (" INHERITS (\""+superclass+"\")") : ""));
     }
     
     
     protected static void updateQuery(String query)
     {
         try {
            System.out.println(query);
            Statement stmt = _db.createStatement();
            stmt.executeUpdate ( query + ";");
         }
         catch (SQLException e)
         {
             System.out.println(e.getMessage());
         }
     }     
     
     
     
     protected static void removeClass(String name)
     {
         try {
            Statement stmt = _db.createStatement();
            stmt.executeUpdate
               ( "DROP TABLE \""+ name + "\"");
            System.out.println("Removed table "+name);
         }
         catch (SQLException e)
         {
             System.out.println(e.getMessage());
         }
     }
     
     
     
     protected static void addGeoField(String cls, String fname, int srid, String type, int dim)
     {
         updateQuery("SELECT AddGeometryColumn('"+cls+"', '"+fname+"', "+srid+", '"+type+"', "+dim+")");
     }
     
     
     
     protected static void remove(Properties p)
     {
         removeClass("AprsPacket"); 
         removeClass("PosReport");     
         removeClass("StatusReport");  
         removeClass("AprsMessage");      
     }
      
      
      
     public static void main(String args[])
     {               
         try {
            /* Get properties from configfile */
            if (args.length < 1)
               System.out.println("Usage: Daemon <config-file>");
            Properties config = new Properties(); 
            FileInputStream fin = new FileInputStream(args[0]);
            config.load(fin);
         
            String url   = config.getProperty
              ("db.url", "jdbc:postgresql://localhost/polaric");   
            String login  = config.getProperty ("db.login");
            String passwd = config.getProperty ("db.passwd");
            System.out.println("url="+url);
            System.out.println("user="+login);

            _db = DriverManager.getConnection(url, login, passwd);
                                   
            /* FIXME: NEED SOME UPGRADE FUNCTION TO KEEP DATA */
            remove(config); 
            
            createClass("AprsPacket", null, 
                               "channel varchar(25) not null, " +
                               "src varchar(10) not null, " +
                               "dest varchar(10) not null, " +
                               "time timestamp without time zone, " +
                               "path text, " +
                               "ipath text, " +
                               "info text " );
                                                                     
                                                                     
            createClass("AprsMessage", null, 
                               "channel varchar(25) not null, " +
                               "src varchar(20) not null, " +
                               "time timestamp without time zone, " +
                               "rtime timestamp without time zone, " +
                               "PRIMARY KEY (time, src) " );
                   // Note that we use timestamp from tracker if available. 
                   // We added received time to be able to join with AprsPacket. 
                   // Normal form? 
                   
                               
            createClass("PosReport", "AprsMessage",
                               "speed  integer, " +
                               "course integer, " +
                               "symbol character default '.'," +
                               "symtab character default '/'," + 
                               "comment text"+
                               "nopkt  boolean");
            addGeoField("PosReport", "position", 4326, "POINT", 2); /* WGS84 Coordinate system */
            
                 /* Consider: Store only changes to position in this class? */
                 
                 
                               
            createClass("StatusReport", "AprsMessage",
                               "dest    varchar(10) not null, " +
                               "info    text ");           
                                                     
                                                     
            createClass("Mission", null, 
                               "src    varchar(20) not null, " +
                               "alias  varchar(30), " +
                               "icon   varchar, " +
                               "tstart  timestamp without time zone not null, "  +
                               "tend    timestamp without time zone, "  + /* NULL means still active */
                               "descr  text, " +
                               "PRIMARY KEY (src, tstart) " );
                /* Assume that you cannot be assigned to more than one mission at a given time */
                
                
                
            createClass("Signs", null,
                               "id          SERIAL PRIMARY KEY, "+
                               "maxscale    integer, "+
                               "icon        text," +
                               "url         text," +
                               "description text," +
                               "picture     boolean default false," +
                               "approved    boolean default false ");
            addGeoField("Signs", "position", 4326, "POINT", 2); /* WGS84 Coordinate system */           
                               
                
            updateQuery("CREATE INDEX geoindex ON \"PosReport\" USING GIST (position);");
            updateQuery("CREATE INDEX geoindex_s ON \"Signs\" USING GIST (position);");
            updateQuery("CREATE INDEX posreport_rtime_idx on \"PosReport\" (rtime);");
            updateQuery("CREATE INDEX posreport_time_src_idx on \"PosReport\" (time,src);");

            _db.close();                    

         }
         catch (Exception e) {
             e.printStackTrace(System.out);
         }
     }
}
