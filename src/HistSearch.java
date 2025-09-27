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
import java.util.*;
import  no.polaric.aprsd.*;



public class HistSearch
{
    public String name, src; 
    public Date tstart, tend; 
  
    public HistSearch(String n, String sr, Date ts, Date te)
    {
        name=n; src=sr;
        tstart = ts; tend = te;
    }
  
}
   
