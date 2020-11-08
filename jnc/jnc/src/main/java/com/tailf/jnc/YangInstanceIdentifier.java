package com.tailf.jnc;

public class YangInstanceIdentifier extends YangBaseString{
	/**
	 * 
	 */
	private static final long serialVersionUID = 4335565686368455056L;
	String value;

	public YangInstanceIdentifier(String value) throws YangException {
		super(value);

	}
	
}
