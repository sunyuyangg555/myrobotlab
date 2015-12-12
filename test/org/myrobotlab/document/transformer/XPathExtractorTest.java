package org.myrobotlab.document.transformer;

import org.myrobotlab.document.Document;

import org.junit.Assert;

public class XPathExtractorTest extends AbstractStageTest {

	@Override
	public Document createDocument() {
		// TODO Auto-generated method stub
		Document doc = new Document("doc_1");
		String xml = "<?xml version=\"1.0\"?><foo><bar>baz</bar></foo>";
		doc.setField("xml", xml);
		return doc;
	}

	@Override
	public AbstractStage createStage() {
		// TODO Auto-generated method stub
		XPathExtractor stage = new XPathExtractor();
		stage.setConfigFile("test/resources/xpaths.txt");
		stage.setXmlField("xml");
		// TODO: I want to remove this whole "stageConfiguration" thing... 
		stage.startStage(null);
		return stage;
	}

	@Override
	public void validate(Document doc) {
		System.out.println(doc);
		Assert.assertEquals(doc.getField("text").get(0).toString(), "baz");

	}

}