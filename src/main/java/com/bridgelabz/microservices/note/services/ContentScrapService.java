package com.bridgelabz.microservices.note.services;

import java.util.List;

import com.bridgelabz.microservices.note.exception.MalFormedException;
import com.bridgelabz.microservices.note.model.UrlMetaData;

public interface ContentScrapService {
	
	public List<UrlMetaData> addContent(String url) throws MalFormedException;
    public List<UrlMetaData> addSplitContent(String description) throws MalFormedException;
}
