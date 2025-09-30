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
import no.polaric.aprsd.*;
import no.polaric.aprsd.point.*;
import java.text.*;
import java.util.*;
import java.io.*;



/*
 * Sample and log some data to the database each hour. 
 */
public class DbStatLogger {
    
    private AprsServerConfig   _api;
    private ServerConfig.Web ws;
    private PluginApi _dbp;
    private long _period = 1000 * 60 * 60;           // 60 minute
    private long _posUpd = TrackerPoint.getPosUpdates();
    private long _aprsPosUpd = TrackerPoint.getAprsPosUpdates();
    private long _req; 
    private long _visits, _logins;  
    private long _mupdates;
    private Timer hb = new Timer();
    
    
    // FIXME: Code to create table and to remove old records
    
    
    public DbStatLogger(AprsServerConfig api) 
    {     
        try {
            _api = api;
            ws = _api.getWebserver();
            _req = ws.nHttpReq();
            _visits = ws.nVisits();
            _logins = ws.nLogins();
            _mupdates = ((MyWebServer) ws).nMapUpdates();
            _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
        
            _dbp.log().info("DbStatLogger", "Starting statistics data logger");
            doStartup();
            
            /* Schedule periodic task */
            hb.schedule( new TimerTask() 
                { public void run() {       
                    doStatLog();
                } 
            } , _period, _period); 
        }
        catch (Exception e) { 
            _api.log().error("DbStatLogger", ""+e); 
            e.printStackTrace(System.out);
        }
    }
    
    
    
    
    public void doStatLog()
    {          
        StatDBSession db = null;
        try {
            db = new StatDBSession(_dbp.getDB());
            db.addStats(new Date(),
                ws.nClients(), ws.nLoggedin(),
                ws.nHttpReq() - _req,
                ws.nVisits() - _visits,
                ws.nLogins() - _logins,
                TrackerPoint.getPosUpdates() - _posUpd,   
                TrackerPoint.getAprsPosUpdates() - _aprsPosUpd, 
                ((MyWebServer)ws).nMapUpdates() - _mupdates);
        
            _req = ws.nHttpReq();
            _visits = ws.nVisits();
            _logins = ws.nLogins();
            _mupdates = ((MyWebServer)ws).nMapUpdates();
            _posUpd = TrackerPoint.getPosUpdates();
            _aprsPosUpd = TrackerPoint.getAprsPosUpdates();
            
            db.commit();
        }
        catch (Exception e)
            { _api.log().error("DbStatLogger", "Periodic Task: "+e); 
               e.printStackTrace(System.out); }           
        finally { 
            if (db != null) db.close(); 
        }          
    }   
    
    
    public void doStartup()
    {          
        StatDBSession db = null; 
        try {
            db = new StatDBSession(_dbp.getDB());
            db.addStartup(new Date());
            db.commit();
        }
        catch (Exception e)
            { _api.log().error("DbStatLogger", "Boot: "+e); 
               e.printStackTrace(System.out); }           
        finally { 
            if (db!=null) db.close(); 
        }          
    }   
}

