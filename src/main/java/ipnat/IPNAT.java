/*
 * #%L
 * hIPNAT plugins for Fiji distribution of ImageJ
 * %%
 * Copyright (C) 2016 Tiago Ferreira
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package ipnat;


import ij.IJ;

public class IPNAT {

	public static final String VERSION = "1.0.1";
	public static final String BUILD = "a";
	public static final String EXTENDED_NAME = "Image Processing for NeuroAnatomy and Tree-like structures";
	public static final String ABBREV_NAME = "hIPNAT";
	public static final String DOC_URL = "http://imagej.net/hIPNAT";
	public static final String SRC_URL = "https://github.com/tferr/hIPNAT";

	public static String getReadableVersion() {
		String s = ABBREV_NAME + " v" + VERSION;
		if (!BUILD.isEmpty())
			s += "-" + BUILD;
		return s;
	}

	public static void handleException(Exception e) {
		IJ.setExceptionHandler(new ipnat.ExceptionHandler());
		IJ.handleException(e);
		IJ.setExceptionHandler(null); // Revert to the default behavior
	}

}
