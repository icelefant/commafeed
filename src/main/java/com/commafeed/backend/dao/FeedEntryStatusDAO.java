package com.commafeed.backend.dao;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.commafeed.backend.FixedSizeSortedSet;
import com.commafeed.backend.model.FeedEntry;
import com.commafeed.backend.model.FeedEntryContent;
import com.commafeed.backend.model.FeedEntryContent_;
import com.commafeed.backend.model.FeedEntryStatus;
import com.commafeed.backend.model.FeedEntryStatus_;
import com.commafeed.backend.model.FeedEntry_;
import com.commafeed.backend.model.FeedSubscription;
import com.commafeed.backend.model.Models;
import com.commafeed.backend.model.User;
import com.commafeed.backend.model.UserSettings.ReadingOrder;
import com.commafeed.backend.services.ApplicationSettingsService;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

@Stateless
public class FeedEntryStatusDAO extends GenericDAO<FeedEntryStatus> {

	protected static Logger log = LoggerFactory
			.getLogger(FeedEntryStatusDAO.class);

	private static final Comparator<FeedEntry> ENTRY_COMPARATOR_DESC = new Comparator<FeedEntry>() {
		@Override
		public int compare(FeedEntry o1, FeedEntry o2) {
			return o2.getUpdated().compareTo(o1.getUpdated());
		};
	};

	private static final Comparator<FeedEntry> ENTRY_COMPARATOR_ASC = new Comparator<FeedEntry>() {
		@Override
		public int compare(FeedEntry o1, FeedEntry o2) {
			return o1.getUpdated().compareTo(o2.getUpdated());
		};
	};

	private static final Comparator<FeedEntryStatus> STATUS_COMPARATOR_DESC = new Comparator<FeedEntryStatus>() {
		@Override
		public int compare(FeedEntryStatus o1, FeedEntryStatus o2) {
			return o2.getEntryUpdated().compareTo(o1.getEntryUpdated());
		};
	};

	private static final Comparator<FeedEntryStatus> STATUS_COMPARATOR_ASC = new Comparator<FeedEntryStatus>() {
		@Override
		public int compare(FeedEntryStatus o1, FeedEntryStatus o2) {
			return o2.getEntryUpdated().compareTo(o1.getEntryUpdated());
		};
	};

	@Inject
	ApplicationSettingsService applicationSettingsService;

	public FeedEntryStatus getStatus(FeedSubscription sub, FeedEntry entry) {

		CriteriaQuery<FeedEntryStatus> query = builder.createQuery(getType());
		Root<FeedEntryStatus> root = query.from(getType());

		Predicate p1 = builder.equal(root.get(FeedEntryStatus_.entry), entry);
		Predicate p2 = builder.equal(root.get(FeedEntryStatus_.subscription),
				sub);

		query.where(p1, p2);

		List<FeedEntryStatus> statuses = em.createQuery(query).getResultList();
		FeedEntryStatus status = Iterables.getFirst(statuses, null);
		if (status == null) {
			status = new FeedEntryStatus(sub.getUser(), sub, entry);
			status.setRead(true);
		}
		return status;
	}

	public List<FeedEntryStatus> findByKeywords(User user,
			List<FeedSubscription> subscriptions, String keywords, int offset,
			int limit, ReadingOrder order) {

		String joinedKeywords = StringUtils.join(
				keywords.toLowerCase().split(" "), "%");
		joinedKeywords = "%" + joinedKeywords + "%";

		int capacity = offset + limit;
		Comparator<FeedEntry> comparator = order == ReadingOrder.desc ? ENTRY_COMPARATOR_DESC
				: ENTRY_COMPARATOR_ASC;
		FixedSizeSortedSet<FeedEntry> set = new FixedSizeSortedSet<FeedEntry>(
				capacity < 0 ? Integer.MAX_VALUE : capacity, comparator);
		for (FeedSubscription sub : subscriptions) {
			CriteriaQuery<FeedEntry> query = builder
					.createQuery(FeedEntry.class);
			Root<FeedEntry> root = query.from(FeedEntry.class);
			Join<FeedEntry, FeedEntryContent> contentJoin = root
					.join(FeedEntry_.content);

			List<Predicate> predicates = Lists.newArrayList();
			predicates.add(builder.equal(root.get(FeedEntry_.feed),
					sub.getFeed()));

			Predicate content = builder.like(
					builder.lower(contentJoin.get(FeedEntryContent_.content)),
					joinedKeywords);
			Predicate title = builder.like(
					builder.lower(contentJoin.get(FeedEntryContent_.title)),
					joinedKeywords);
			predicates.add(builder.or(content, title));

			query.where(predicates.toArray(new Predicate[0]));
			orderEntriesBy(query, root, order);
			TypedQuery<FeedEntry> q = em.createQuery(query);
			limit(q, 0, capacity);
			setTimeout(q);

			List<FeedEntry> list = q.getResultList();
			for (FeedEntry entry : list) {
				entry.setSubscription(sub);
			}
			set.addAll(list);
		}

		List<FeedEntry> entries = set.asList();
		int size = entries.size();
		if (size < offset) {
			return Lists.newArrayList();
		}

		entries = entries.subList(offset, size);

		List<FeedEntryStatus> results = Lists.newArrayList();
		for (FeedEntry entry : entries) {
			FeedSubscription subscription = entry.getSubscription();
			results.add(getStatus(subscription, entry));
		}

		return lazyLoadContent(true, results);
	}

	public List<FeedEntryStatus> findStarred(User user, Date newerThan,
			int offset, int limit, ReadingOrder order, boolean includeContent) {

		CriteriaQuery<FeedEntryStatus> query = builder.createQuery(getType());
		Root<FeedEntryStatus> root = query.from(getType());

		List<Predicate> predicates = Lists.newArrayList();

		predicates.add(builder.equal(root.get(FeedEntryStatus_.user), user));
		predicates.add(builder.equal(root.get(FeedEntryStatus_.starred), true));
		query.where(predicates.toArray(new Predicate[0]));

		if (newerThan != null) {
			predicates.add(builder.greaterThanOrEqualTo(
					root.get(FeedEntryStatus_.entryInserted), newerThan));
		}

		orderStatusesBy(query, root, order);

		TypedQuery<FeedEntryStatus> q = em.createQuery(query);
		limit(q, offset, limit);
		setTimeout(q);
		return lazyLoadContent(includeContent, q.getResultList());
	}

	public List<FeedEntryStatus> findBySubscriptions(User user,
			List<FeedSubscription> subscriptions, Date newerThan, int offset,
			int limit, ReadingOrder order, boolean includeContent) {

		int capacity = offset + limit;
		Comparator<FeedEntry> comparator = order == ReadingOrder.desc ? ENTRY_COMPARATOR_DESC
				: ENTRY_COMPARATOR_ASC;
		FixedSizeSortedSet<FeedEntry> set = new FixedSizeSortedSet<FeedEntry>(
				capacity < 0 ? Integer.MAX_VALUE : capacity, comparator);
		for (FeedSubscription sub : subscriptions) {
			CriteriaQuery<FeedEntry> query = builder
					.createQuery(FeedEntry.class);
			Root<FeedEntry> root = query.from(FeedEntry.class);

			List<Predicate> predicates = Lists.newArrayList();
			predicates.add(builder.equal(root.get(FeedEntry_.feed),
					sub.getFeed()));

			if (newerThan != null) {
				predicates.add(builder.greaterThanOrEqualTo(
						root.get(FeedEntry_.inserted), newerThan));
			}
			query.where(predicates.toArray(new Predicate[0]));
			orderEntriesBy(query, root, order);
			TypedQuery<FeedEntry> q = em.createQuery(query);
			limit(q, 0, capacity);
			setTimeout(q);

			List<FeedEntry> list = q.getResultList();
			for (FeedEntry entry : list) {
				entry.setSubscription(sub);
			}
			set.addAll(list);
		}

		List<FeedEntry> entries = set.asList();
		int size = entries.size();
		if (size < offset) {
			return Lists.newArrayList();
		}

		entries = entries.subList(offset, size);

		List<FeedEntryStatus> results = Lists.newArrayList();
		for (FeedEntry entry : entries) {
			FeedSubscription subscription = entry.getSubscription();
			results.add(getStatus(subscription, entry));
		}

		return lazyLoadContent(includeContent, results);
	}

	public List<FeedEntryStatus> findUnreadBySubscriptions(User user,
			List<FeedSubscription> subscriptions, Date newerThan, int offset,
			int limit, ReadingOrder order, boolean includeContent) {

		int capacity = offset + limit;
		Comparator<FeedEntryStatus> comparator = order == ReadingOrder.desc ? STATUS_COMPARATOR_DESC
				: STATUS_COMPARATOR_ASC;
		FixedSizeSortedSet<FeedEntryStatus> set = new FixedSizeSortedSet<FeedEntryStatus>(
				capacity < 0 ? Integer.MAX_VALUE : capacity, comparator);
		for (FeedSubscription sub : subscriptions) {
			CriteriaQuery<FeedEntryStatus> query = builder
					.createQuery(getType());
			Root<FeedEntryStatus> root = query.from(getType());

			List<Predicate> predicates = Lists.newArrayList();

			predicates.add(builder.equal(
					root.get(FeedEntryStatus_.subscription), sub));
			predicates.add(builder.isFalse(root.get(FeedEntryStatus_.read)));

			if (newerThan != null) {
				predicates.add(builder.greaterThanOrEqualTo(
						root.get(FeedEntryStatus_.entryInserted), newerThan));
			}

			query.where(predicates.toArray(new Predicate[0]));
			orderStatusesBy(query, root, order);

			TypedQuery<FeedEntryStatus> q = em.createQuery(query);
			limit(q, -1, limit);
			setTimeout(q);

			List<FeedEntryStatus> list = q.getResultList();
			set.addAll(list);
		}

		List<FeedEntryStatus> entries = set.asList();
		int size = entries.size();
		if (size < offset) {
			return Lists.newArrayList();
		}

		entries = entries.subList(offset, size);
		return lazyLoadContent(includeContent, entries);
	}

	private List<FeedEntryStatus> lazyLoadContent(boolean includeContent,
			List<FeedEntryStatus> results) {
		if (includeContent) {
			for (FeedEntryStatus status : results) {
				Models.initialize(status.getSubscription().getFeed());
				Models.initialize(status.getEntry().getContent());
			}
		}
		return results;
	}

	private void orderEntriesBy(CriteriaQuery<?> query,
			Path<FeedEntry> entryJoin, ReadingOrder order) {
		orderBy(query, entryJoin.get(FeedEntry_.updated), order);
	}

	private void orderStatusesBy(CriteriaQuery<?> query,
			Path<FeedEntryStatus> statusJoin, ReadingOrder order) {
		orderBy(query, statusJoin.get(FeedEntryStatus_.entryUpdated), order);
	}

	private void orderBy(CriteriaQuery<?> query, Path<Date> date,
			ReadingOrder order) {
		if (order != null) {
			if (order == ReadingOrder.asc) {
				query.orderBy(builder.asc(date));
			} else {
				query.orderBy(builder.desc(date));
			}
		}
	}

	protected void setTimeout(Query query) {
		setTimeout(query, applicationSettingsService.get().getQueryTimeout());
	}

	/**
	 * Map between subscriptionId and unread count
	 */
	@SuppressWarnings("rawtypes")
	public Map<Long, Long> getUnreadCount(User user) {
		Map<Long, Long> map = Maps.newHashMap();
		Query query = em.createNamedQuery("EntryStatus.unreadCounts");
		query.setParameter("user", user);
		setTimeout(query);
		List resultList = query.getResultList();
		for (Object o : resultList) {
			Object[] array = (Object[]) o;
			map.put((Long) array[0], (Long) array[1]);
		}
		return map;
	}

	public void markSubscriptionEntries(User user,
			List<FeedSubscription> subscriptions, Date olderThan) {
		List<FeedEntryStatus> statuses = findUnreadBySubscriptions(user,
				subscriptions, null, -1, -1, null, false);
		markList(statuses, olderThan);
	}

	public void markStarredEntries(User user, Date olderThan) {
		List<FeedEntryStatus> statuses = findStarred(user, null, -1, -1, null,
				false);
		markList(statuses, olderThan);
	}

	private void markList(List<FeedEntryStatus> statuses, Date olderThan) {
		List<FeedEntryStatus> list = Lists.newArrayList();
		for (FeedEntryStatus status : statuses) {
			if (!status.isRead()) {
				Date inserted = status.getEntry().getInserted();
				if (olderThan == null || inserted == null
						|| olderThan.after(inserted)) {
					if (status.isStarred()) {
						status.setRead(true);
						list.add(status);
					} else {
						delete(status);
					}

				}
			}
		}
		saveOrUpdate(list);
	}

}
