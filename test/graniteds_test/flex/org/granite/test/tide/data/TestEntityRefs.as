package org.granite.test.tide.data
{
    import flash.events.TimerEvent;
    import flash.system.System;
    import flash.utils.Timer;
    import flash.utils.getQualifiedClassName;
    
    import mx.collections.ArrayCollection;
    import mx.collections.IList;
    
    import org.flexunit.Assert;
    import org.flexunit.async.Async;
    import org.granite.persistence.PersistentSet;
    import org.granite.test.tide.Contact;
    import org.granite.test.tide.Person;
    import org.granite.tide.BaseContext;
    import org.granite.tide.Tide;
    
    
    public class TestEntityRefs 
    {
        private var _ctx:BaseContext = Tide.getInstance().getContext();
        
        
        [Before]
        public function setUp():void {
            Tide.resetInstance();
            _ctx = Tide.getInstance().getContext();
        }
        
        
        private var contactsNew:ArrayCollection;
        
		[Ignore("Test cannot be reproduced reliably due to unpredictable GC")]
        [Test(async)]
        public function testEntityRefs():void {
        	Tide.getInstance().setComponentRemoteSync("person", Tide.SYNC_BIDIRECTIONAL);
        	
        	var person:Person = new Person();
        	person.id = 1;
        	person.uid = "P01"; 
        	person.version = 0;
        	var contacts:ArrayCollection = new ArrayCollection();
        	person.contacts = contacts;
        	var contact:Contact = new Contact();
        	contact.id = 1;
        	contact.uid = "C01";
        	contact.version = 0;
        	contact.person = person;
        	person.contacts.addItem(contact);
        	_ctx.person = _ctx.meta_mergeExternalData(person);
        	person = _ctx.person;
        	
        	Assert.assertEquals("Person bound", "person", _ctx.meta_getReference(person).path);
			Assert.assertEquals("Contacts bound", "person", _ctx.meta_getReference(contacts).path);
			Assert.assertEquals("Contact bound", "person", _ctx.meta_getReference(contact).path);
			
			contacts = null;
			contactsNew = new ArrayCollection();
			person.contacts = contactsNew;
        	
        	Assert.assertEquals("Person still bound", "person", _ctx.meta_getReference(person).path);
			Assert.assertNull("Contacts unbound", _ctx.meta_getReference(contacts));
			Assert.assertNull("Contact unbound", _ctx.meta_getReference(contact));
			
			contact = null;
			
			var person1:Person = new Person();
        	person1.id = 1; 
        	person1.uid = person.uid;
        	person1.version = 1;
			var contactsNew1:ArrayCollection = new ArrayCollection();
			person1.contacts = contactsNew1;
			_ctx.meta_mergeExternalData(person1);	// Clear dirty cache of person
			
			System.gc();	// Force gc to clear refs on contact, works only in debug player
			
			var timer:Timer = new Timer(100);
			timer.addEventListener(TimerEvent.TIMER, Async.asyncHandler(this, nextPart, 1000));
			timer.start();
        }
        
        private function nextPart(event:Object, pass:Object = null):void {			
			var person2:Person = new Person();
			person2.id = 1;
			person2.uid = "P01";
			person2.version = 2;
			var contacts2:ArrayCollection = new ArrayCollection();
			person2.contacts = contacts2;
			var contact2:Contact = new Contact();
			contact2.id = 1;
			contact2.uid = "C01";
			contact2.version = 1;
			contact2.person = person2;
			person2.contacts.addItem(contact2);
			
			_ctx.meta_mergeExternalData(person2);
			
			Assert.assertEquals("Contacts rebound", "person", _ctx.meta_getReference(contactsNew).path);
			Assert.assertEquals("Contact rebound", "person", _ctx.meta_getReference(contact2).path);
			
			var person3:Person = new Person();
			person3.id = 1;
			person3.uid = "P01";
			person3.version = 3;
			var contacts3:ArrayCollection = new ArrayCollection();
			person3.contacts = contacts3;
			_ctx.meta_mergeExternalData(person3);
			
			Assert.assertEquals("Contacts bound", "person", _ctx.meta_getReference(contactsNew).path);
			Assert.assertEquals("Contact unbound", "person", _ctx.meta_getReference(contact2).path);
        }
		
		
		[Test]
		public function testEntityRefs2():void {
			var person:Person = new Person(1, 0, "test", "test");
			person.contacts = new ArrayCollection();
			var contact:Contact = new Contact(1, 0, person, "test@test.com");
			person.contacts.addItem(contact);
			
			person = Person(_ctx.meta_mergeExternalData(person));
			
			var refs:Array = _ctx.meta_getRefs(person.contacts.list);
			Assert.assertEquals("Ref person count", 1, refs.length);
			Assert.assertEquals("Ref person uid", getQualifiedClassName(Person) + ":" + person.uid, refs[0][0]);
			
			var oldlist:IList = person.contacts.list;

			var person1:Person = new Person(1, 0, "test", "test");
			person1.contacts = new ArrayCollection();
			var contact1:Contact = new Contact(1, 0, person, "test@test.com");
			person1.contacts.addItem(contact1);
			
			_ctx.meta_mergeExternalData(person1);
			
			var person2:Person = new Person(1, 0, "test", "test");
			person2.contacts = new PersistentSet();
			var contact2:Contact = new Contact(1, 0, person, "test@test.com");
			person2.contacts.addItem(contact2);
			
			_ctx.meta_mergeExternalData(person2);
			
			refs = _ctx.meta_getRefs(person.contacts.list);
			Assert.assertEquals("Ref person count", 1, refs.length);
			Assert.assertEquals("Ref person uid", getQualifiedClassName(Person) + ":" + person.uid, refs[0][0]);
			Assert.assertNull("Ref to old list removed", _ctx.meta_getRefs(oldlist));
		}
    }
}
