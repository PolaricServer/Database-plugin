/* 
 * Copyright (C) 2014-2023 by Øyvind Hanssen (ohanssen@acm.org)
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
import java.util.function.*;



public class DbList<T> implements Iterable<T>, Iterator<T>
{

    public static interface Factory<T>
    {
        public T getElement(ResultSet s) throws SQLException; 
    }


    private ResultSet _rs, _rs1, _rs2; 
    private String _fieldname; 
    private Factory<T> _fact, _fact1, _fact2; 
    private boolean _empty; 
            
    protected synchronized void _init(ResultSet rs, String fn, Factory<T> f)
    {
        _rs=_rs1=rs; 
        _fieldname = fn;
        _fact = _fact1 = f; 
         try { reset(); } 
         catch (Exception ex) {
            System.out.println("DbList._init: "+ex);
         } 
    }
    

    public DbList (ResultSet rs, Factory<T> f)
       { _init(rs, null, f); }
       
       
    public DbList (ResultSet rs, String fn)
       { _init(rs, fn, null); } 

       
    public synchronized void merge(DbList<T> x) {
      try {
        _rs2 = x._rs;
        _fact2 = x._fact;
        reset();
      }
      catch (SQLException ex) 
         { System.out.println("DbList.merge: "+ex); } 
    }
    
       
    public boolean isEmpty() 
       { return _empty; }
    
       
    public synchronized void reset() throws SQLException
    {
      _rs = _rs1;
      _fact = _fact1;
      _empty = !_rs.first();
      if (!_empty) 
        _rs.beforeFirst();
      else if (_rs2 != null) {
        _rs = _rs2; 
        _fact = _fact2; 
      }  
      if (_rs2 != null && _rs2.first()) {
        _rs2.beforeFirst();
        _empty = false; 
      }
    }
 
 
    /*
     * Interface Iterable<T>
     */
    public Iterator<T> iterator() 
    {
      return this; 
    }
 
    
    
    private boolean switchRs() throws SQLException {
      if (_rs==_rs1 && _rs2 != null && _rs.isLast()) {
        _rs = _rs2;
        _fact = _fact2;
        if (!_rs.first())
           _empty = true;
        _rs.beforeFirst();
        return true;
      }
      else 
        return false; 
    }
    
    
          
    /*
     * Interface Iterator<T>
     */
    public synchronized boolean hasNext() 
    {
      try {
        if (_empty)
          return false;
        switchRs();
        return (!_empty && !_rs.isLast()); 
      }
      catch (SQLException ex) 
         { System.out.println("DbList.hasNext: "+ex); return false; } 
    }


    
    @SuppressWarnings("unchecked")
    public synchronized T next() 
    {
      try {
        while (true) {
          if (_rs.next()) {
            if (_fieldname != null) 
                return (T) _rs.getObject(_fieldname);
            else
                return (T) _fact.getElement(_rs); 
          }
          else
            if (!switchRs()) return null;
        }
       }
      catch (SQLException ex) 
        { System.out.println("DbList.next: "+ex); return null; }  
    }

    
    public void remove() 
       { }  
       
       
    public synchronized List<T> toList() {
        List<T> list = new ArrayList<T>(); 
        for (T x : this)
            list.add(x);
        return list;
    }
    
}

   
