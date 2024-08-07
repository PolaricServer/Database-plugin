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
import  no.polaric.aprsd.*;
import java.util.*;
import no.polaric.aprsd.Signs;


/* Consider moving this to core package */

public class Sign extends Signs.Item
{
  public static class Category
  {
      public int id; 
      public String name; 
      public String icon;
     
      public Category(int id, String name, String icon)
      {
         this.id = id; 
         this.name = name; 
         this.icon = icon;
      }
  }
  private int cls; 
  private String userid;
  private String group; 
  
  public int getCategory() 
    { return cls; }
    
  public String getGroup()
    { return group; }
    
  @Override public String getUser()
    { return userid; }
  
  public Sign (String i, LatLng r, long sc, String ic, String url, String txt, int cls) {
    this(i,r,sc,ic,url,txt,cls,null);
  }

  public Sign (String i, LatLng r, long sc, String ic, String url, String txt, int cls, String grp) {
    this(i,r,sc,ic,url,txt,cls,grp,null);
  }
  
  public Sign (String i, LatLng r, long sc, String ic, String url, String txt, int cls, String grp, String user) {
     super(i,r,sc,ic,url,txt); 
     this.cls = cls;
     this.group = grp;
     this.userid = user;
  }
  
}
   
