package aceim.protocol.snuk182.xmpp.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.jivesoftware.smack.RosterEntry;
import org.jivesoftware.smack.RosterGroup;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.Presence.Mode;
import org.jivesoftware.smack.packet.Presence.Type;
import org.jivesoftware.smack.packet.RosterPacket;
import org.jivesoftware.smack.util.StringUtils;
import org.jivesoftware.smackx.Form;
import org.jivesoftware.smackx.FormField;
import org.jivesoftware.smackx.FormField.Option;
import org.jivesoftware.smackx.MessageEventManager;
import org.jivesoftware.smackx.muc.HostedRoom;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.Occupant;
import org.jivesoftware.smackx.muc.RoomInfo;

import aceim.api.dataentity.Buddy;
import aceim.api.dataentity.BuddyGroup;
import aceim.api.dataentity.InputFormFeature;
import aceim.api.dataentity.MultiChatRoom;
import aceim.api.dataentity.OnlineInfo;
import aceim.api.dataentity.PersonalInfo;
import aceim.api.dataentity.ProtocolServiceFeatureTarget;
import aceim.api.dataentity.TextMessage;
import aceim.api.dataentity.tkv.ListTKV;
import aceim.api.dataentity.tkv.StringTKV;
import aceim.api.dataentity.tkv.StringTKV.ContentType;
import aceim.api.dataentity.tkv.TKV;
import aceim.api.dataentity.tkv.ToggleTKV;
import aceim.api.service.ApiConstants;
import aceim.api.utils.Logger;
import aceim.api.utils.Logger.LoggerLevel;
import android.content.Context;
import android.text.TextUtils;

public class XMPPEntityAdapter {
	
	static final byte INVISIBLE_STATUS_ID = 5;
	private static final Mode[] presenceModes = {Mode.available, Mode.away, Mode.xa, Mode.dnd, Mode.chat};
	
	public TextMessage xmppMessage2TextMessage(Message message, XMPPServiceInternal service, boolean resourceAsWriterId){
		if (message == null){
			return null;
		}
		
		TextMessage txtMessage;
		if (resourceAsWriterId){
			txtMessage = new TextMessage(service.getOnlineInfo().getServiceId(), StringUtils.parseBareAddress(message.getFrom()));
			txtMessage.setContactDetail(StringUtils.parseResource(message.getFrom()));
		} else {
			String from	= normalizeJID(message.getFrom());
			txtMessage = new TextMessage(service.getOnlineInfo().getServiceId(), from);
		}
		txtMessage.setMessageId(message.getPacketID() != null ? message.getPacketID().hashCode() : message.hashCode());
		txtMessage.setTime(System.currentTimeMillis());
		txtMessage.setText(message.getBody());
		txtMessage.setIncoming(true);
		
		return txtMessage;
	}

	public Presence userStatus2XMPPPresence(Byte status) {
		Presence presence;
		
		if (status < 0 || status >= presenceModes.length) {
			presence = new Presence(Type.unavailable);
		} else {
			presence = new Presence(Type.available);
			presence.setMode(presenceModes[status]);
		}
		
		return presence;
	}
	
	public byte xmppPresence2UserStatus(Presence presence) {
		if (presence == null || presence.getType() != Type.available){
			return -1;
		}
		
		if (presence.getMode() == null || presence.getMode() == Mode.available){
			return 0;
		}
		
		for (byte i=0; i<presenceModes.length; i++) {
			Mode m = presenceModes[i];
			if (m == presence.getMode()){
				return i;
			}
		}
		
		return -1;
	}
	
	public OnlineInfo presence2OnlineInfo(Presence presence, Context context, byte serviceId, String ownerJid, OnlineInfo onlineInfo){
		if (presence == null){
			return null;
		}
		
		String jid = normalizeJID(presence.getFrom());

		OnlineInfo info;		
		if (onlineInfo != null && jid.equals(onlineInfo.getProtocolUid())) {
			info = onlineInfo;
		} else {
			info = new OnlineInfo(serviceId, jid);
		}
		
		String resource = StringUtils.parseResource(presence.getFrom());
		if (!TextUtils.isEmpty(resource)) {
			info.getFeatures().putString(ApiConstants.FEATURE_BUDDY_RESOURCE, resource);
		}
		
		info.getFeatures().putByte(ApiConstants.FEATURE_STATUS, xmppPresence2UserStatus(presence));
		info.setXstatusName(presence.getStatus());

		info.getFeatures().putBoolean(ApiConstants.FEATURE_FILE_TRANSFER, true);
		
		return info;
	}
	
	public String normalizeJID(String jid){
		return StringUtils.parseBareAddress(jid);
	}
	
	public String getClientId(String jid){
		if (jid == null){
			return null;
		}
		if (jid.indexOf("/")>-1){
			return jid.split("/")[1];
		}
		
		return jid;
	}

	public Buddy rosterEntry2Buddy(RosterEntry entry, String ownerJid, Context context, byte serviceId){
		if (entry == null){
			return null;
		}
		Buddy buddy = new Buddy(normalizeJID(entry.getUser()), ownerJid, XMPPApiConstants.PROTOCOL_NAME, serviceId);
		buddy.setName(entry.getName());
		buddy.setId(entry.getUser().hashCode());
		buddy.setGroupId((entry.getGroups() != null && entry.getGroups().size() > 0) ? entry.getGroups().iterator().next().getName() : ApiConstants.NO_GROUP_ID);
		//buddy.clientId = getClientId(entry.getUser());
		
		if (entry.getStatus()!=null && entry.getStatus().equals(RosterPacket.ItemStatus.SUBSCRIPTION_PENDING)){
			//buddy.visibility = Buddy.VIS_NOT_AUTHORIZED;
		}
		
		return buddy;
	}
	
	public BuddyGroup rosterGroup2BuddyGroup(RosterGroup entry, Collection<RosterEntry> buddies, String ownerUid, Context context, byte serviceId){
		if (entry == null){
			return null;
		}
		BuddyGroup group = new BuddyGroup(entry.getName(), ownerUid, serviceId);
		group.setName(entry.getName());
		
		for (RosterEntry buddy: entry.getEntries()){
			buddies.remove(buddy);
			group.getBuddyList().add(rosterEntry2Buddy(buddy, ownerUid, context, serviceId));
		}
		return group;
	}
	
	public List<BuddyGroup> rosterGroupCollection2BuddyGroupList(Collection<RosterGroup> entries, Collection<RosterEntry> buddies, String ownerUid, Context context, byte serviceId){
		if (entries == null){
			return null;
		}
		
		List<RosterEntry> list = new ArrayList<RosterEntry>(buddies);
		
		List<BuddyGroup> groups = new ArrayList<BuddyGroup>(entries.size());
		for (RosterGroup entry: entries){
			groups.add(rosterGroup2BuddyGroup(entry, list, ownerUid, context, serviceId));
		}
		
		BuddyGroup noGroup = new BuddyGroup(ApiConstants.NO_GROUP_ID, ownerUid, serviceId);
		for (RosterEntry buddyWithNoGroup : list) {
			noGroup.getBuddyList().add(rosterEntry2Buddy(buddyWithNoGroup, ownerUid, context, serviceId));
		}
		groups.add(noGroup);
		
		return groups;
	}

	public List<PersonalInfo> xmppHostedRooms2MultiChatRooms(Collection<HostedRoom> hostedRooms, String ownerJid, byte serviceId) {
		if (hostedRooms == null){
			return Collections.emptyList();
		}
		
		List<PersonalInfo> chats = new ArrayList<PersonalInfo>(hostedRooms.size());
		for (HostedRoom room: hostedRooms){
			chats.add(xmppHostedRoom2PersonalInfo(room, serviceId));
		}
		return chats;
	}

	private PersonalInfo xmppHostedRoom2PersonalInfo(HostedRoom room, byte serviceId) {
		if (room == null) {
			return null;
		}
		
		PersonalInfo info = new PersonalInfo(serviceId);
		info.setProtocolUid(room.getJid());
		info.setMultichat(true);
		info.getProperties().putString(PersonalInfo.INFO_NICK, room.getName());	
		
		return info;
	}

	public MultiChatRoom xmppHostedRoom2MultiChatRoom(HostedRoom room, String ownerJid, byte serviceId) {
		MultiChatRoom chat = new MultiChatRoom(room.getJid(), ownerJid, XMPPApiConstants.PROTOCOL_NAME, serviceId);
		chat.setName(room.getName());
		return chat;
	}

	public MultiChatRoom xmppRoomInfo2MultiChatRoom(RoomInfo info, String ownerJid, byte serviceId) {
		MultiChatRoom chat = new MultiChatRoom(info.getRoom(), ownerJid, XMPPApiConstants.PROTOCOL_NAME, serviceId);
		chat.setName(info.getDescription());
		return chat;
	}

	public MultiChatRoom chatRoomInfo2Buddy(RoomInfo info, String ownerJid, byte serviceId, boolean joined) {
		MultiChatRoom chat = new MultiChatRoom(info.getRoom(), ownerJid, XMPPApiConstants.PROTOCOL_NAME, serviceId);
		chat.setName((info.getSubject()!= null && info.getSubject().length() > 0) ? info.getSubject() : info.getRoom());
		chat.getOnlineInfo().setXstatusName(info.getDescription());
		chat.setId(chat.getProtocolUid().hashCode());
		
		if (joined){
			chat.getOnlineInfo().getFeatures().putByte(ApiConstants.FEATURE_STATUS, (byte) 0);
		}
		
		return chat;
	}

	public MultiChatRoom chatInfo2Buddy(String chatId, String chatName, String ownerJid, byte serviceId, boolean joined) {
		MultiChatRoom chat = new MultiChatRoom(chatId, ownerJid, XMPPApiConstants.PROTOCOL_NAME, serviceId);
		chat.setName(chatName);
		chat.setId(chat.getProtocolUid().hashCode());

		if (joined){
			chat.getOnlineInfo().getFeatures().putByte(ApiConstants.FEATURE_STATUS, (byte) 0);
		}
		
		return chat;
	}

	public Message textMessage2XMPPMessage(TextMessage textMessage, String thread, String to, Message.Type messageType) throws Exception {
		Message message = new Message(to, messageType);
		message.setThread(thread);
		message.setPacketID(textMessage.getMessageId() + "");
		MessageEventManager.addNotificationsRequests(message, true, true, true, true);
		
		message.setBody(textMessage.getText());	
		
		return message;
	}
	
	public List<BuddyGroup> xmppMUCOccupants2mcrOccupants(XMPPServiceInternal service, MultiUserChat muc, boolean loadIcons) {
		List<BuddyGroup> groups = new ArrayList<BuddyGroup>();
		String ownerJid = service.getOnlineInfo().getProtocolUid();
		
		BuddyGroup moderators = new BuddyGroup(Integer.toString(2), ownerJid, service.getOnlineInfo().getServiceId());
		BuddyGroup participants = new BuddyGroup(Integer.toString(5), ownerJid, service.getOnlineInfo().getServiceId());
		BuddyGroup other = new BuddyGroup(Integer.toString(7), ownerJid, service.getOnlineInfo().getServiceId());
		BuddyGroup all = new BuddyGroup(Integer.toString(8), ownerJid, service.getOnlineInfo().getServiceId());
		//TODO
		moderators.setName("Moderators");
		participants.setName("Participants");
		other.setName("Other");
		all.setName("All");
		
		Map<String, Buddy> buddies = new HashMap<String, Buddy>();
		
		Iterator<String> it = muc.getOccupants(); 
		
		for (;it.hasNext();){
			String occupant = it.next();
			String buddyId;
			Occupant occu = muc.getOccupant(occupant);
			if (occu != null && occu.getJid() != null){
				buddyId = normalizeJID(occu.getJid());
				if (loadIcons){
					try {
						service.loadCard(buddyId);
					} catch (Exception e) {
						Logger.log(e);
					}
				}
			} else {
				buddyId = occupant;
			}
			Buddy buddy = new Buddy(buddyId, ownerJid, XMPPApiConstants.PROTOCOL_NAME, service.getOnlineInfo().getServiceId());
			buddy.setName(buddyId.equals(occupant) ? StringUtils.parseResource(occupant) : occu.getNick());
			buddy.getOnlineInfo().getFeatures().putByte(ApiConstants.FEATURE_STATUS, xmppPresence2UserStatus(muc.getOccupantPresence(occupant)));
			
			buddies.put(buddy.getProtocolUid(), buddy);
			buddy.setId(buddyId.hashCode());
			all.getBuddyList().add(buddy);
		}
		
		try {
			fillMUCGroup(muc.getParticipants(), participants, buddies);
			fillMUCGroup(muc.getModerators(), moderators, buddies);
			other.getBuddyList().addAll(buddies.values());
			
			groups.add(moderators);
			groups.add(participants);
			groups.add(other);
		} catch (Exception e1) {
			Logger.log(e1);
		}
		
		if (groups.size() < 1){
			groups.add(all);			
		}
		
		return groups;
	}

	private void fillMUCGroup(Collection<Occupant> occupants, BuddyGroup group, Map<String, Buddy> map) {
		for (Occupant occu : occupants){
			String occupantJid = normalizeJID(occu.getJid());
			Buddy occupant = map.remove(occupantJid);
			if (occupant != null) {
				group.getBuddyList().add(occupant);
			}
		}		
	}

	public RosterEntry buddy2RosterEntry(XMPPConnection connection, Buddy buddy) {		
		return connection.getRoster().getEntry(buddy.getProtocolUid());
	}

	public RosterGroup buddyGroup2RosterEntry(XMPPConnection connection, BuddyGroup buddyGroup) {
		for (RosterGroup group: connection.getRoster().getGroups()){
			if (group.getName().equals(buddyGroup.getId())){
				return group;
			}
		}
		return null;
	}

	public void addGroupChats(List<BuddyGroup> groups, List<MultiChatRoom> joinedChatRooms, String ownerUid, byte serviceId) {
		BuddyGroup noGroup = null;
		
		for (BuddyGroup g: groups) {
			if (g.getId().equals(ApiConstants.NO_GROUP_ID)) {
				noGroup = g;
				break;
			}
		}
		
		if (noGroup == null) {
			noGroup = new BuddyGroup(ApiConstants.NO_GROUP_ID, ownerUid, serviceId);			
		}
		
		noGroup.getBuddyList().addAll(joinedChatRooms);
	}

	public InputFormFeature chatRoomConfigurationForm2InputFormFeature(Form form, Context context) {
		if (form == null) {
			return null;
		}
		
		List<TKV> tkvs = new ArrayList<TKV>();
		
		for (Iterator<FormField> fieldIterator = form.getFields(); fieldIterator.hasNext();){
			FormField field = fieldIterator.next();
			
			String type = field.getType();
			
			TKV tkv = null;
			if (type.equalsIgnoreCase(FormField.TYPE_TEXT_SINGLE) || type.equalsIgnoreCase(FormField.TYPE_TEXT_MULTI) || type.equalsIgnoreCase(FormField.TYPE_JID_SINGLE) || type.equalsIgnoreCase(FormField.TYPE_JID_MULTI)) {
				tkv = new StringTKV(ContentType.STRING, field.getLabel(), field.isRequired(), null);				
			} else if (type.equalsIgnoreCase(FormField.TYPE_TEXT_PRIVATE)) {
				tkv = new StringTKV(ContentType.PASSWORD, field.getLabel(), field.isRequired(), null);	
			} else if (type.equalsIgnoreCase(FormField.TYPE_LIST_SINGLE) || type.equalsIgnoreCase(FormField.TYPE_LIST_MULTI)) {
				tkv = new ListTKV(chatFormListItemChoices2ListTkvChoices(field.getOptions()), field.getLabel(), field.isRequired(), null);
			} else if (type.equalsIgnoreCase(FormField.TYPE_BOOLEAN)) {
				tkv = new ToggleTKV(field.getLabel(), field.isRequired(), Boolean.parseBoolean(field.getVariable()));
			} else {
				Logger.log("Unsupportable field type: " + type + "/" + field.getLabel(), LoggerLevel.INFO);
			}
			
			if (tkv != null) {
				tkvs.add(tkv);
			}
		}
		
		InputFormFeature feature = new InputFormFeature(
				XMPPApiConstants.FEATURE_CONFIGURE_CHAT_ROOM_RESULT, 
				context.getString(R.string.room_configuration), 
				android.R.drawable.ic_menu_edit, 
				false,
				false,
				tkvs.toArray(new TKV[tkvs.size()]), 
				new ProtocolServiceFeatureTarget[]{ProtocolServiceFeatureTarget.BUDDY});
		
		return feature;
	}

	private String[] chatFormListItemChoices2ListTkvChoices(Iterator<Option> options) {
		if (options == null) {
			return new String[0];
		}
		
		List<String> list = new ArrayList<String>();
		
		for (;options.hasNext();) {
			Option o = options.next();
			list.add(o.getLabel());
		}
		
		return list.toArray(new String[list.size()]);
	}
}
