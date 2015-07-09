/**
 * Copyright (C) 2009-2015 Dell, Inc.
 * See annotations for authorship information
 *
 * ====================================================================
 * Licensed under the Apache License\tVersion 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing\tsoftware
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND\teither express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */
package org.dasein.cloud.aliyun.platform.model;

import static org.junit.Assert.*;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;

import org.dasein.cloud.InternalException;
import org.junit.Test;

/**
 * Tests for DatabaseProvider
 * @author Jane Wang
 * @since 2015.05.01
 */
public class DatabaseProviderTests {

	@Test
	public void testFromFile() {
		try {
			
			DatabaseProvider provider = DatabaseProvider.fromFile("/platform/dbproducts.json", "Aliyun");
			assertNotNull(provider);
			
			DatabaseEngine engine = provider.findEngine("MySQL");
			assertNotNull(engine);
			
			for (DatabaseRegion region: engine.getRegions()) {
				for (String name : region.getName()) {
					System.out.print(name + " ");
				}
				System.out.println();
				for (DatabaseProduct product: region.getProducts()) {
					System.out.println("      " + product.getName() + ": " + product.getCurrency() + "\t" + product.getHourlyPrice() + 
							"\t" + product.getLicense() + "\t" + product.getMaxConnection() + "\t" + product.getMaxIops() + 
							"\t" + product.getMemory() + "\t" + product.getMinStorage());
				}
			}
			
		} catch (InternalException e) {
			e.printStackTrace();
		}
	}
	
	@Test
	public void testBasic() {
		List<String> accessList = new ArrayList<String>();
		accessList.add("172.168.2.0/8");
		accessList.add("www.sinal.com");
		accessList.add("168.192.3.4");
		StringBuilder accessBuilder = new StringBuilder();
		Iterator<String> access = accessList.iterator();
		while (access.hasNext()) {
			String cidr = access.next();
			if (!cidr.equals("168.192.3.4")) {
				accessBuilder.append(cidr + ",");
			}
		}
		System.out.println(accessBuilder.toString());
		accessBuilder.deleteCharAt(accessBuilder.length() - 1);
		System.out.println(accessBuilder.toString());
	}
	
	@Test
	public void testDatetime() throws ParseException {
		Calendar cal = Calendar.getInstance();
		cal.set(Calendar.HOUR_OF_DAY, 6);
		SimpleDateFormat format = new SimpleDateFormat("HH:00'Z'");
		System.out.println(format.format(cal.getTime()));
		cal.set(Calendar.HOUR_OF_DAY, 23);
		System.out.println(format.format(cal.getTime()));
		
		format = new SimpleDateFormat("EEEE");
		cal.set(Calendar.DAY_OF_WEEK, 2);
		System.out.println(format.format(cal.getTime()));
		
		SimpleDateFormat formatter = new SimpleDateFormat("HH:mm'Z'");
		String timeStr = "06:32Z";
		System.out.println(formatter.parse(timeStr).getHours() + ", " + formatter.parse(timeStr).getMinutes());
	}
	
}
