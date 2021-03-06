package com.dynatrace.utils;

import java.io.IOException;
import java.io.InputStream;

public interface Source<T extends Source<T>> {

	String id();
	String name();
	InputStream openStream() throws IOException;
	long length();
	long lastModified();
	T localize() throws IOException;
	
}
