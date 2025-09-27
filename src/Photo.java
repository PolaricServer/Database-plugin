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
import  no.polaric.aprsd.*;
import  no.polaric.aprsd.point.*;
import java.util.*;
import no.polaric.aprsd.Signs;


/* Consider moving this to core package */

public class Photo extends Signs.Item
{
  private String userid;
  private Date time;
  private byte[] image;
    
  public String getUser() { 
     return userid; 
  }
    
  public Date getTime() {
     return time;
  }
  
  public byte[] getImage() {
    return image;
  }
  
  public Photo (String i, Date t, LatLng r, String uid, String descr) {
    super(i, r, 0, "signs/camera.png", "P", descr);
    setType("photo");
    this.userid = uid;
    this.time = t;
  }

  public Photo (String i, Date t, LatLng r, String uid, String descr, byte[] img)  {
    this(i, t, r, uid, descr); 
    this.image = img;
  }
  
}
   
