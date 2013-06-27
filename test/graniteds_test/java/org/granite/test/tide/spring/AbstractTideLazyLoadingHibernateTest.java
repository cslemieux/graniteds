package org.granite.test.tide.spring;

import javax.inject.Inject;

import org.granite.context.GraniteContext;
import org.granite.messaging.amf.io.util.ClassGetter;
import org.granite.test.tide.data.Person;
import org.hibernate.Criteria;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.Restrictions;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;


public class AbstractTideLazyLoadingHibernateTest extends AbstractTideTestCase {
	
	@Inject
	private SessionFactory sessionFactory;
	
	@Inject
	private PlatformTransactionManager txManager;
    
	@Test
    public void testLazyLoading() {
		TransactionDefinition def = new DefaultTransactionDefinition();
		
		TransactionStatus tx = txManager.getTransaction(def);
		Person person = new Person();
		person.initUid();
		sessionFactory.getCurrentSession().persist(person);
		txManager.commit(tx);
		
		tx = txManager.getTransaction(def);
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(Person.class);
		criteria.add(Restrictions.idEq(person.getId()));
		person = (Person)criteria.uniqueResult();
		txManager.commit(tx);
		
        Person result = (Person)initializeObject(person, new String[] { "contacts" });
        
        ClassGetter classGetter = GraniteContext.getCurrentInstance().getGraniteConfig().getClassGetter();
        Assert.assertTrue("Person initialized", classGetter.isInitialized(null, null, result));
        Assert.assertTrue("Collection initialized", classGetter.isInitialized(result, "contacts", result.getContacts()));
        
		Assert.assertEquals("Sessions closed", sessionFactory.getStatistics().getSessionOpenCount(), 
				sessionFactory.getStatistics().getSessionCloseCount());
    }
}
