package aceim.protocol.snuk182.xmpp.common;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.Roster;
import org.jivesoftware.smack.RosterEntry;
import org.jivesoftware.smack.RosterGroup;
import org.jivesoftware.smack.RosterListener;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.RoomInfo;
import org.jivesoftware.smackx.packet.VCard;

import aceim.api.dataentity.Buddy;
import aceim.api.dataentity.BuddyGroup;
import aceim.api.dataentity.ItemAction;
import aceim.api.dataentity.OnlineInfo;
import aceim.api.dataentity.PersonalInfo;
import aceim.api.dataentity.ServiceMessage;
import aceim.api.service.ApiConstants;
import aceim.api.utils.Logger;
import aceim.api.utils.Logger.LoggerLevel;

public class XMPPRosterListener extends XMPPListener implements RosterListener, PacketListener, PacketFilter {

	private volatile boolean isContactListReady = false;
	
	//private final List<OnlineInfo> infos = Collections.synchronizedList(new ArrayList<OnlineInfo>());
	private final Map<String, OnlineInfo> presenceCache = new ConcurrentHashMap<String, OnlineInfo>();
	
	public XMPPRosterListener(XMPPServiceInternal service) {
		super(service);
	}

	@Override
	public void entriesUpdated(Collection<String> addresses) {
		clUpdated();
	}

	@Override
	public void entriesDeleted(Collection<String> addresses) {
		clUpdated();
	}

	@Override
	public void entriesAdded(Collection<String> addresses) {
		clUpdated();
	}
	
	void clUpdated(){
		List<BuddyGroup> groups = getContactList();
		
		getInternalService().getService().getCoreService().buddyListUpdated( groups);
	}
	
	List<BuddyGroup> getContactList() {
		Roster roster = getInternalService().getConnection().getRoster();
		
		Collection<RosterEntry> entries = roster.getEntries();
		for (RosterEntry entry : entries) {
			if (entry.getName() == null) {
				try {
					VCard vc = new VCard();
					vc.load(getInternalService().getConnection(), entry.getUser());
					String nick = getNicknameFromVCard(vc);
					
					if (nick != null) {
						entry.setName(nick);
					}
				} catch (XMPPException e) {
					Logger.log(e);
				}
			}
		}
		
		List<BuddyGroup> groups = getInternalService().getService().getEntityAdapter().rosterGroupCollection2BuddyGroupList(roster.getGroups(), entries, getInternalService().getOnlineInfo().getProtocolUid(), getInternalService().getService().getContext(), getInternalService().getService().getServiceId());
		return groups;
	}

	@Override
	public void presenceChanged(Presence presence) {
		Logger.log(" - presence " + presence.getFrom() + " " + presence.getMode(), LoggerLevel.VERBOSE);

		OnlineInfo info = getInternalService().getService().getEntityAdapter().presence2OnlineInfo(presence, getInternalService().getService().getContext(), getInternalService().getService().getServiceId(), getInternalService().getService().getProtocolUid(), presenceCache.get(getInternalService().getService().getEntityAdapter().normalizeJID(presence.getFrom())));
		
		presenceCache.put(info.getProtocolUid(), info);
		
		if (isContactListReady) {
			getInternalService().getService().getCoreService().buddyStateChanged(Arrays.asList(info));
		} 
	}

	void checkCachedInfos() {
		while (presenceCache.size() > 0) {
			getInternalService().getService().getCoreService().buddyStateChanged(new ArrayList<OnlineInfo>(presenceCache.values()));
			presenceCache.clear();
		}
	}
	
	@Override
	public boolean accept(Packet packet) {
		if (packet instanceof Presence) {
			Presence p = (Presence) packet;
			return ((Presence.Type.subscribe.equals(p.getType())) 
				|| (Presence.Type.unsubscribe.equals(p.getType()))
				|| (Presence.Type.subscribed.equals(p.getType()))
				|| (Presence.Type.unsubscribed.equals(p.getType())));
		}
		return false;
	}

	@Override
	public void processPacket(Packet packet) {
		if (packet instanceof Presence) {
			Presence p = (Presence) packet;
			if (Presence.Type.subscribe.equals(p.getType())) {	
				ServiceMessage message = new ServiceMessage(getInternalService().getService().getServiceId(), getInternalService().getService().getEntityAdapter().normalizeJID(p.getFrom()), true);
				message.setText(p.getFrom());
				message.setContactDetail(getInternalService().getService().getContext().getString(R.string.ask_authorization));
				
				getInternalService().getService().getCoreService().message(message);
			
			} else if (Presence.Type.unsubscribe.equals(p.getType())) {					
				Presence ppacket = new Presence(Presence.Type.unsubscribed);
				ppacket.setTo(packet.getFrom());
				ppacket.setFrom(packet.getTo());
				getInternalService().getConnection().sendPacket(ppacket);					
			} else if (Presence.Type.unsubscribed.equals(p.getType()) || Presence.Type.subscribed.equals(p.getType())) {
				clUpdated();
			} else {
				presenceChanged(p);
			}
		}
	}
	
	void renameBuddy(Buddy buddy) {
		Roster roster = getInternalService().getConnection().getRoster();
		RosterEntry buddyEntry = roster.getEntry(buddy.getProtocolUid());
		buddyEntry.setName(buddy.getName());
		getInternalService().getService().getCoreService().buddyAction(ItemAction.MODIFIED, buddy);
	}

	void renameGroup(final BuddyGroup buddyGroup) {
		new Thread() {

			@Override
			public void run() {
				try {
					RosterGroup rgroup = getInternalService().getService().getEntityAdapter().buddyGroup2RosterEntry(getInternalService().getConnection(), buddyGroup);

					for (RosterEntry entry : rgroup.getEntries()) {
						getInternalService().getConnection().getRoster().createEntry(entry.getUser(), entry.getName(), new String[] { buddyGroup.getName() });
					}

					getInternalService().getService().getCoreService().groupAction(ItemAction.MODIFIED, buddyGroup);
				} catch (Exception e) {
					Logger.log(e);
					getInternalService().getService().getCoreService().notification( e.getLocalizedMessage());
				}
			}
		}.start();
	}

	void moveBuddy(final Buddy buddy) {
		new Thread() {
			@Override
			public void run() {
				try {
					Roster roster = getInternalService().getConnection().getRoster();
					roster.createEntry(buddy.getProtocolUid(), buddy.getName(), (buddy.getGroupId() != null && buddy.getGroupId().equals(ApiConstants.NO_GROUP_ID)) ? new String[] { buddy.getGroupId() } : new String[0]);
					buddy.setGroupId(buddy.getGroupId());
					getInternalService().getService().getCoreService().buddyAction(ItemAction.MODIFIED, buddy);
				} catch (XMPPException e) {
					Logger.log(e);
					getInternalService().getService().getCoreService().notification( e.getLocalizedMessage());
				}
			}
		}.start();
	}

	void removeGroup(final BuddyGroup buddyGroup) {
		new Thread() {

			@Override
			public void run() {
				try {
					RosterGroup rgroup = getInternalService().getService().getEntityAdapter().buddyGroup2RosterEntry(getInternalService().getConnection(), buddyGroup);
					Roster roster = getInternalService().getConnection().getRoster();
					
					for (RosterEntry entry : rgroup.getEntries()) {
						//getInternalService().getConnection().getRoster().removeEntry(entry);
						
						roster.createEntry(entry.getUser(), entry.getName(), new String[0]);
						
						Buddy buddy = getInternalService().getService().getEntityAdapter().rosterEntry2Buddy(entry, buddyGroup.getOwnerUid(), getInternalService().getService().getContext(), buddyGroup.getServiceId());
						buddy.setGroupId(ApiConstants.NO_GROUP_ID);
						
						getInternalService().getService().getCoreService().buddyAction(ItemAction.MODIFIED, buddy);
					}

					getInternalService().getService().getCoreService().groupAction(ItemAction.DELETED, buddyGroup);
				} catch (Exception e) {
					Logger.log(e);
					getInternalService().getService().getCoreService().notification( e.getLocalizedMessage());
				}
			}
		}.start();
	}

	void removeBuddy(final Buddy buddy) {
		new Thread() {

			@Override
			public void run() {
				try {
					getInternalService().getConnection().getRoster().removeEntry(getInternalService().getService().getEntityAdapter().buddy2RosterEntry(getInternalService().getConnection(), buddy));
					getInternalService().getService().getCoreService().buddyAction(ItemAction.DELETED, buddy);
				} catch (XMPPException e) {
					Logger.log(e);
					getInternalService().getService().getCoreService().notification( e.getLocalizedMessage());
				}
			}

		}.start();
	}

	void addGroup(final BuddyGroup buddyGroup) {
		new Thread() {

			@Override
			public void run() {
				BuddyGroup g = new BuddyGroup(buddyGroup.getName(), buddyGroup.getOwnerUid(), buddyGroup.getServiceId());
				g.setName(buddyGroup.getName());
				
				getInternalService().getConnection().getRoster().createGroup(g.getName());
				getInternalService().getService().getCoreService().groupAction(ItemAction.ADDED, g);
			}
		}.start();
	}

	void addBuddy(final Buddy buddy) {
		Runnable r = new Runnable() {
			
			@Override
			public void run() {
				try {
					XMPPCommonService service = getInternalService().getService();
					Roster roster = getInternalService().getConnection().getRoster();
					roster.createEntry(buddy.getProtocolUid(), buddy.getName(), (buddy.getGroupId() != null && !buddy.getGroupId().equals(ApiConstants.NO_GROUP_ID)) ? new String[] { buddy.getGroupId() } : new String[0]);
					service.getCoreService().buddyAction(
							ItemAction.ADDED, 
							service.getEntityAdapter().rosterEntry2Buddy(
									roster.getEntry(buddy.getProtocolUid()), 
									service.getProtocolUid(), 
									service.getContext(), 
									service.getServiceId()));
				} catch (XMPPException e) {
					Logger.log(e);
					getInternalService().getService().getCoreService().notification( e.getLocalizedMessage());
				}
			}
		};
		
		Executors.defaultThreadFactory().newThread(r).start();
	}

	/**
	 * @return the isContactListReady
	 */
	public boolean isContactListReady() {
		return isContactListReady;
	}

	/**
	 * @param isContactListReady the isContactListReady to set
	 */
	public void setContactListReady(boolean isContactListReady) {
		this.isContactListReady = isContactListReady;
	}
	
	public void loadCard(String jid) {
		Executors.defaultThreadFactory().newThread(new PersonalInfoRunnable(jid, PersonalInfoTarget.ICON, false)).start();
	}
	
	public void getBuddyInfo(String jid, boolean shortInfo, boolean isMultiUserChat) {
		PersonalInfoTarget target;
		
		if (shortInfo) {
			target = PersonalInfoTarget.SHORT;
		} else {
			target = PersonalInfoTarget.ALL;
		}
		
		Executors.defaultThreadFactory().newThread(new PersonalInfoRunnable(jid, target, isMultiUserChat)).start();
	}
	
	@Override
	void onDisconnect() {
		presenceCache.clear();
	}

	private String getNicknameFromVCard(VCard card) {
		String fn;
		if (card.getNickName() != null && card.getNickName().length() > 0) {
			fn = card.getNickName();
		} else {
			fn = card.getField("FN");
		}
		
		return fn;
	}
	
	/**
	 * @return the presenceCache
	 */
	public Map<String, OnlineInfo> getPresenceCache() {
		return presenceCache;
	}

	private class PersonalInfoRunnable implements Runnable {

		private final String uid;
		private final PersonalInfoTarget target;
		private final boolean isMultiUserChat;

		private PersonalInfoRunnable(String uid, PersonalInfoTarget target, boolean isMultiUserChat) {
			this.uid = uid;
			this.target = target;
			this.isMultiUserChat = isMultiUserChat;
		}

		@Override
		public void run() {
			PersonalInfo info = new PersonalInfo(getInternalService().getService().getServiceId());
			info.setProtocolUid(uid);
			
			VCard card = new VCard();
			
			if (isMultiUserChat) {
				try {
					RoomInfo room = MultiUserChat.getRoomInfo(getInternalService().getConnection(), uid);
					info.getProperties().putCharSequence(PersonalInfo.INFO_CHAT_DESCRIPTION, room.getDescription());
					info.getProperties().putCharSequence(PersonalInfo.INFO_CHAT_OCCUPANTS, room.getOccupantsCount() + "");
					info.getProperties().putCharSequence(PersonalInfo.INFO_CHAT_SUBJECT, room.getSubject());
					
					getInternalService().getService().getCoreService().personalInfo(info, false);
					return;
				} catch (XMPPException e) {
					Logger.log(e.getLocalizedMessage(), LoggerLevel.DEBUG);
				}
			}
			
			try {
				card.load(getInternalService().getConnection(), uid);
				
				switch (target) {
				case ALL:
					for (String prop: getAllFieldsOfCard(card).keySet()){
						info.getProperties().putCharSequence(prop, card.getField(prop));							
					}
				case SHORT:
					String fn = getNicknameFromVCard(card);
					
					if (fn != null) {
						info.getProperties().putString(PersonalInfo.INFO_NICK, fn);
					}
					getInternalService().getService().getCoreService().personalInfo(info, true);
				case ICON:
					if (card.getAvatar() != null) {
						getInternalService().getService().getCoreService().iconBitmap( uid, card.getAvatar(), card.getAvatarHash());
					}
					break;
				
				}
			} catch (XMPPException e) {
				Logger.log(e);
			}			
		}
		

		@SuppressWarnings("unchecked")
		private final Map<String, String> getAllFieldsOfCard(VCard card) {
			try {
				Field f = VCard.class.getDeclaredField("otherSimpleFields");
				f.setAccessible(true);
				return (Map<String, String>) f.get(card);
			} catch (Exception e) {
				Logger.log(e);
			}
			
			return Collections.emptyMap();
		}
	}
	
	private enum PersonalInfoTarget {
		ALL,
		SHORT,
		ICON
	}

	public void authorizationResponse(String contactUid, boolean accept) {
		Presence subscribe = new Presence(accept ? Presence.Type.subscribed : Presence.Type.unsubscribed);
	    subscribe.setTo(contactUid);
	    getInternalService().getConnection().sendPacket(subscribe);
	}

	public void uploadIcon(final byte[] bytes) {
		Executors.defaultThreadFactory().newThread(new Runnable() {
			
			@Override
			public void run() {
				try {
					VCard card = new VCard();
					card.load(getInternalService().getConnection(),getInternalService().getService().getProtocolUid());
					card.setAvatar(bytes);
					card.save(getInternalService().getConnection());
					getInternalService().getService().getCoreService().iconBitmap(getInternalService().getService().getProtocolUid(), bytes, card.getAvatarHash());
				} catch (XMPPException e) {
					Logger.log(e);
				}
			}
		}).start();
	}
}
