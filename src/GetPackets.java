

package no.polaric.aprsdb;
import java.sql.*;
import javax.sql.*;
import org.apache.commons.dbcp.*; 
import  no.polaric.aprsd.*;
import java.util.*;
import java.io.*;
import uk.me.jstott.jcoord.*;
import org.postgis.PGgeometry;



public class GetPackets {

    static protected BasicDataSource _dsrc;
    static private Connection _con;
    
    
    /** 
     *  Return a list of the last n APRS packets from a given call.
     *
     * @param src from callsign
     * @param n   number of elements of list
     */
    static public DbList<AprsPacket> getAprsPackets(String src, int n)
       throws java.sql.SQLException
    {    
        PreparedStatement stmt = _con.prepareStatement
           ( " SELECT * FROM \"AprsPacket\"" +
             " WHERE src=?"  + 
             " ORDER BY time DESC LIMIT ?",
             ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
       
        stmt.setString(1, src);
        stmt.setInt(2, n);
        
        return new DbList(stmt.executeQuery(), rs -> 
            {
                AprsPacket p =  new AprsPacket();
                String path = rs.getString("path");
                String ipath = rs.getString("ipath");
                
                p.source = null;
                p.from = rs.getString("src");
                p.to = rs.getString("dest");
                p.via = (path==null ? "" : rs.getString("path") + ", ") + rs.getString("ipath");
                p.report = rs.getString("info");
                p.time = rs.getTimestamp("time");
                return p;
             });
    }     
         
    
    
    /**
     * Parse mic-e data.
     * Based on http://www.aprs-is.net/javAPRS/mice_parser.htm
     */
    private static void parseMicE (AprsPacket p, String station, java.util.Date time) 
    {
            String toField = p.to;
            String msg = p.report;
            int j, k;
            Double pos_lat, pos_long; 
            AprsHandler.PosData pd = new AprsHandler.PosData();

            if (toField.length() < 6 || msg.length() < 9) 
                return;
            
            boolean isCustom = ((toField.charAt(0) >= 'A'
                         && toField.charAt(0) <= 'K') || (toField.charAt(1) >= 'A'
                         && toField.charAt(1) <= 'K') || (toField.charAt(2) >= 'A'
                         && toField.charAt(2) <= 'K'));
                         
            for (j = 0; j < 3; j++)
            {
                  if (isCustom)
                  {
                        if (toField.charAt(j) < '0'
                                || toField.charAt(j) > 'L'
                                || (toField.charAt(j) > '9'
                                        && toField.charAt(j) < 'A'))
                                return;
                  }
                  else
                  {
                        if (toField.charAt(j) < '0'
                                || toField.charAt(j) > 'Z'
                                || (toField.charAt(j) > '9'
                                        && toField.charAt(j) < 'L')
                                || (toField.charAt(j) > 'L'
                                        && toField.charAt(j) < 'P'))
                                return;
                  }
            }
            for (;j < 6; j++)
            {
                 if (toField.charAt(j) < '0'
                         || toField.charAt(j) > 'Z'
                         || (toField.charAt(j) > '9'
                                 && toField.charAt(j) < 'L')
                         || (toField.charAt(j) > 'L'
                                 && toField.charAt(j) < 'P'))
                         return;
            }
            if (toField.length() > 6)
            {
                  if (toField.charAt(6) != '-'
                          || toField.charAt(7) < '0'
                          || toField.charAt(7) > '9')
                          return;
                  if (toField.length() == 9)
                  {
                          if (toField.charAt(8) < '0'
                                  || toField.charAt(8) > '9')
                                  return;
                  }
            }
            // Parse the "TO" field
            int c = cnvtDest(toField.charAt(0));
            int mes = 0; 
            if ((c & 0x10) != 0) mes = 0x08; // Set the custom flag
            if (c >= 0x10) mes += 0x04;
            int d = (c & 0xf) * 10;  // Degrees
            c = cnvtDest(toField.charAt(1));
            if (c >= 0x10) mes += 0x02;
            d += (c & 0xf);
            c = cnvtDest(toField.charAt(2));
            if (c >= 0x10) mes++;
            int m = (c & 0xf) * 10;  // Minutes
            c = cnvtDest(toField.charAt(3));
            boolean north = (c >= 0x20);
            m += (c & 0xf);
            c = cnvtDest(toField.charAt(4));
            boolean hund = (c >= 0x20);
            int s = (c & 0xf) * 10;  // Hundredths of minutes
            c = cnvtDest(toField.charAt(5));
            boolean west = (c >= 0x20);
            s += (c & 0xf);

            pos_lat = d + m/60.0 + s/6000.0;
            if (!north) pos_lat *= -1.0;
                    
            pd.symbol = msg.charAt(7);
            pd.symtab = msg.charAt(8);
               
            // Parse the longitude
            d = msg.charAt(1)-28;
            m = msg.charAt(2)-28;
            s = msg.charAt(3)-28;
                    
            if (d < 0 || d > 199
                    || m < 0 || m > 120
                    || s < 0 || s > 120)
                    return;

            // Adjust the degrees value
            if (hund) d += 100;
            if (d >= 190) d -= 190;
            else if (d >= 180) d -= 80;
                    
            // Adjust minutes 0-9 to proper spot
            if (m >= 60) m -= 60;
                    
            pos_long = d + m/60.0 + s/6000.0;
            if (west) pos_long *= -1.0;

            pd.pos = new LatLng(pos_lat, pos_long);
                    
            // Parse the Speed/Course (s/d)
            m = msg.charAt(5)-28;  // DC+28
            if (m < 0 || m > 97) return;
            s = msg.charAt(4)-28;
            if (s < 0 || s > 99) return;
            s = (s*10) + (m/10);  //Speed (Knots)
            d = msg.charAt(6)-28;
            if (d < 0 || d > 99) return;
            d = ((m%10)*100) + d;  // Course
            // Specification decoding method
            if (s>=800) s -= 800;
            if (d>=400) d -= 400;
            pd.course = d;
            
            pd.speed = (int) Math.round(s * 1.852);          // Km / h   
            pd.altitude = -1; 
            String comment = null;
            if (msg.length() > 9)
            {  
               char typecode = msg.charAt(9);
               j = msg.indexOf('}', 9);
               if (j >= 9 + 3) {
                  pd.altitude = (int)Math.round(((((((msg.charAt(j-3)-33)*91) 
                                               + (msg.charAt(j-2)-33))*91) 
                                               + (msg.charAt(j-1)-33))-10000));
                  if (msg.length() > j) 
                      comment = msg.substring(j+1); 
               }
               else 
                  comment = msg.substring(10);
                  
               if (typecode==' ');
               if (typecode==']' || typecode=='>') {
                  if (comment.length() > 0 && comment.charAt(comment.length() -1) == '=')
                     comment = comment.substring(0, comment.length()-1);
               }   
               else if (typecode=='`' || typecode == '\'') {
                 if (comment.length() < 2);
                 else if (typecode=='`' &&  comment.charAt(comment.length() -1)=='_')
                    comment = comment.substring(0, comment.length()-1);
                 else
                    comment = comment.substring(0, comment.length()-2);
               }
               else if (pd.altitude == -1)
                    comment = typecode+comment;
            }     
            if (comment != null){
                comment = comment.trim();   
                if (comment.length() == 0)
                   comment = null;
            }     
            
            handlePosReport(p.source, station, time, pd, comment, p.report );
            return;
    }
    
    

    private static int cnvtDest (int inchar)
    {
            int c = inchar - 0x30;           // Adjust all to be 0 based
            if (c == 0x1c) c = 0x0a;         // Change L to be a space digit
            if (c > 0x10 && c <= 0x1b) --c;  // A-K need to be decremented
            
            // Space is converted to 0
            // as javAPRS does not support ambiguity
            if ((c & 0x0f) == 0x0a) c &= 0xf0;
            return c;
    }
     
     
    /**
      * Encode a single character for use in a SQL query
      */
    private static String qChar(char x)
    {
        if (x=='\'') return "'\'\''"; 
        else return "'" + x + "'";
    }
      
      
    /**
      * Encode and add a position to a PostGIS SQL statement.
      * FIXME: THIS IS DUPLICATED IN MyDBSession 
      */
    private static void setRef(PreparedStatement stmt, int index, Reference pos)
         throws SQLException
    {
         LatLng ll = pos.toLatLng();
         org.postgis.Point p = new org.postgis.Point( ll.getLng(), ll.getLat() );
         p.setSrid(4326);
         stmt.setObject(index, new PGgeometry(p));
    }
      

    
    public static void handlePosReport(Source chan, String sender, java.util.Date ts, AprsHandler.PosData pd,
            String descr, String pathinfo)
    {
        try {
            PreparedStatement stmt = _con.prepareStatement
                ( "INSERT INTO \"PosReport\" (channel, src, time, rtime, speed, course, position, symbol, symtab, comment, nopkt)" + 
               " VALUES (?, ?, ?, ?, ?, ?, ?, "+qChar(pd.symbol)+", "+qChar(pd.symtab)+", ?, ?)" );
            stmt.setString(1, "database");
            stmt.setString(2, sender);
            stmt.setTimestamp(3, DBSession.date2ts(ts));
            stmt.setTimestamp(4, DBSession.date2ts(ts));
            stmt.setInt(5, pd.speed);
            stmt.setInt(6, pd.course);
            setRef(stmt, 7, pd.pos);      
            stmt.setString(8, descr);
            stmt.setBoolean(9, ("(EXT)".equals(pathinfo.toUpperCase())));
            stmt.executeUpdate(); 
            _con.commit();
            System.out.println("*** Database updated");
        }
        catch (NullPointerException e)
        {
            System.out.println("handlePosReport: "+e);
            e.printStackTrace(System.out);
            try{ _con.rollback(); } catch(Exception ex) {}
        }
        catch (Exception e)
        {
           System.out.println("handlePosReport: "+e);  
           try{ _con.rollback(); } catch(Exception ex) {}
        }
    }
    
    
            
    public static void main(String args[])
    {               
        try {
            /* Get properties from configfile */
            if (args.length < 2)
               System.out.println("Usage: Daemon <config-file> <callsign>");
            Properties config = new Properties(); 
            FileInputStream fin = new FileInputStream(args[0]);
            config.load(fin);
            String call=args[1];
        
            Properties pp = new Properties();
            pp.put("accessToUnderlyingConnectionAllowed", "true");
            BasicDataSourceFactory dsf = new BasicDataSourceFactory();
            _dsrc = (BasicDataSource) dsf.createDataSource(pp);
            _dsrc.setDriverClassName("org.postgresql.Driver");
            _dsrc.setUsername(config.getProperty("db.login"));
            _dsrc.setPassword(config.getProperty("db.passwd"));
            _dsrc.setUrl(config.getProperty("db.url")); 
            _dsrc.setValidationQuery("select true");
            _con = _dsrc.getConnection(); 
            _con.setAutoCommit(false);
            
            DbList<AprsPacket> pkts = getAprsPackets(call, 1000); 
            for (AprsPacket p: pkts) {
                System.out.println(p.time+" :: "+p.from+">"+p.to+", "+p.via+": "+p.report);
                parseMicE(p, call, p.time);
            }
            _con.close();
        }       
        catch (Exception e) {
            e.printStackTrace(System.out);
        }
    }

}

