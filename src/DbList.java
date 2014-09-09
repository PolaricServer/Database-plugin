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
import java.util.*;
import java.lang.reflect.*;  

/* Denne er eksperimentell - just testing */


public class DbList<T> implements Iterable<T>, Iterator<T>
{

    public static interface Factory<T>
    {
        public T getElement(ResultSet s) throws SQLException; 
    }


    private ResultSet _rs; 
    private String _fieldname; 
    private Factory<T> _fact; 
    private boolean _empty; 
            
    protected void _init(ResultSet rs, String fn, Factory<T> f)
    {
        _rs=rs; 
        _fieldname = fn;
        _fact = f; 
         try { reset(); } 
         catch (Exception e) {} 
    }
    

    public DbList (ResultSet rs, Factory<T> f)
       { _init(rs, null, f); }
       
    public DbList (ResultSet rs, String fn)
       { _init(rs, fn, null); } 

    public void reset() throws SQLException
    {
        _empty = !_rs.first(); 
        _rs.beforeFirst();
    }
 
 
    /*
     * Interface Iterable<T>
     */
    public Iterator<T> iterator() 
    {
      //  reset(); 
        return this; 
    }
 
          
    /*
     * Interface Iterator<T>
     */
    public boolean hasNext() 
    {
      try {
        return (! (_empty ||  _rs.isLast()));  // Can be somewhat expensive?
      }
      catch (SQLException ex) 
         { System.out.println("DbList.hasNext: "+ex); return false; } 
    }
    
   
    public T next() 
    {
       try {
         if (_rs.next()) {
            if (_fieldname != null) 
                 return (T) _rs.getObject(_fieldname);
            else
                 return (T) _fact.getElement(_rs); 
         }
         return null; 
       }
       catch (SQLException ex) 
          { System.out.println("DbList.next: "+ex); return null; }  
    }
    
    
    public void remove() 
       { }  
    
}

   
