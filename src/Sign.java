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
import java.util.*;
import no.polaric.aprsd.Signs;
import uk.me.jstott.jcoord.*;

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
  
  public int getCategory() 
    { return cls; }
  
  public Sign (int i, Reference r, long sc, String ic, String url, String txt, int cls) {
     super(i,r,sc,ic,url,txt); 
     this.cls = cls;
  }
  
}
   
