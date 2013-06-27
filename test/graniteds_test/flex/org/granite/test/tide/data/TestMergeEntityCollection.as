package org.granite.test.tide.data
{
    import mx.collections.ArrayCollection;
    import mx.events.CollectionEvent;
    import mx.events.CollectionEventKind;
    
    import org.flexunit.Assert;
    import org.granite.persistence.PersistentSet;
    import org.granite.test.tide.Contact;
    import org.granite.test.tide.Person;
    import org.granite.test.tide.Person0;
    import org.granite.tide.BaseContext;
    import org.granite.tide.Tide;
    
    
    public class TestMergeEntityCollection 
    {
        private var _ctx:BaseContext = Tide.getInstance().getContext();
        
        
        [Before]
        public function setUp():void {
            Tide.resetInstance();
            _ctx = Tide.getInstance().getContext();
        }
        
        
        [Test]
        public function testMergeEntityCollection():void {
        	var p1:Person0 = new Person0(1, "A1", "B1");
        	var p2:Person0 = new Person0(2, "A2", "B2");
        	var coll:ArrayCollection = new ArrayCollection();
        	coll.addItem(p1);
        	coll.addItem(p2);
        	coll.addItem(p2);
        	coll.addItem(p1);
        	_ctx.meta_mergeExternalData(coll);
        	
        	var p1b:Person0 = new Person0(1, "A1", "B1");
        	var p2b:Person0 = new Person0(2, "A2", "B2");
        	var coll2:ArrayCollection = new ArrayCollection();
        	coll2.addItem(p1b);
        	coll2.addItem(p2b);
        	coll2.addItem(p2b);
        	coll2.addItem(p1b);
        	_ctx.meta_mergeExternalData(coll2, coll);
        	
        	Assert.assertEquals("Element 0", 1, coll.getItemAt(0).id);
        	Assert.assertEquals("Element 1", 2, coll.getItemAt(1).id);
        	Assert.assertEquals("Element 2", 2, coll.getItemAt(2).id);
        	Assert.assertEquals("Element 3", 1, coll.getItemAt(3).id);
        }

		
		[Test]
		public function testMergeEntityCollection2():void {
			var changes:int = 0;
			
			var p1:Person = new Person(1, 0, "A1", "B1");
			var c1:Contact = new Contact();
			c1.uid = "C1";
			p1.contacts = new PersistentSet();
			p1.contacts.addEventListener(CollectionEvent.COLLECTION_CHANGE, function(event:CollectionEvent):void {
				if (event.kind == CollectionEventKind.ADD)
					changes++;
			}, false, 0, true);
			p1.contacts.addItem(c1);
			
			Assert.assertEquals("Changes", 1, changes);
			
			changes = 0;
			
			var p2:Person = new Person(1, 1, "A1", "B1");
			var c2:Contact = new Contact(1, 0, p2, "C1");
			p2.contacts = new PersistentSet();
			p2.contacts.addItem(c2);
			_ctx.meta_mergeExternalData(p2, p1);
			
			var c3:Contact = new Contact();
			
			p1.contacts.addItem(c3);
			
			Assert.assertEquals("Changes", 1, changes);
		}
		
		[Test]
		public function testMergeEntityCollection3():void {
			var p1:Person = new Person(1, 0, "A1", "B1");
			p1.contacts = new PersistentSet();
			p1 = _ctx.meta_mergeExternalData(p1) as Person;
			
			var p1b:Person = new Person(1, 0, "A1", "B1");
			p1b.contacts = new PersistentSet();
			var c1b:Contact = new Contact(1, 0, p1b, "C1");
			p1b.contacts.addItem(c1b);
			
			_ctx.meta_mergeExternalData(c1b);
			
			Assert.assertEquals("Element added", 1, p1.contacts.length);
		}
		
		[Test]
		public function testMergeEntityCollection4():void {
			var u1:User2 = new User2(1, 0, "A1", "B1");
			u1.meetings = new PersistentSet();
			u1 = _ctx.meta_mergeExternalData(u1) as User2;
			
			var u1b:User2 = new User2(1, 0, "A1", "B1");
			u1b.meetings = new PersistentSet();
			var c1b:Client = new Client(1, 0, "A1", "B1");
			c1b.meetings = new PersistentSet();
			var m1b:Meeting = new Meeting(1, 0, c1b, u1b);
			c1b.meetings.addItem(m1b);
			u1b.meetings.addItem(m1b);
			
			_ctx.meta_mergeExternalData(m1b);
			
			Assert.assertEquals("Element added", 1, u1.meetings.length);
		}
	}
}
