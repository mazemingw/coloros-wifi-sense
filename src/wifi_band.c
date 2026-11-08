/* Root-only, one-shot QCA nl80211 GETBAND/SETBAND transport. No scans/files. */
#include <errno.h>
#include <linux/genetlink.h>
#include <linux/netlink.h>
#include <linux/nl80211.h>
#include <net/if.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#define QCA_OUI 0x001374
#define QCA_SETBAND 105
#define QCA_GETBAND 192
#define QCA_BAND_MASK 43
struct message { struct nlmsghdr h; struct genlmsghdr g; unsigned char data[512]; };
static uint32_t sequence;
static int sock;

static int fail(const char *what) { fprintf(stderr,"%s: %s\n",what,strerror(errno)); return -1; }
static void init(struct message *m, unsigned type, unsigned command) {
    memset(m,0,sizeof(*m)); m->h.nlmsg_len=NLMSG_LENGTH(GENL_HDRLEN);
    m->h.nlmsg_type=type; m->h.nlmsg_flags=NLM_F_REQUEST|NLM_F_ACK;
    m->h.nlmsg_seq=++sequence; m->g.cmd=command; m->g.version=1;
}
static void attr(struct message *m, unsigned type, const void *data, size_t len) {
    size_t pos=NLMSG_ALIGN(m->h.nlmsg_len), total=NLA_HDRLEN+len;
    if(pos+NLA_ALIGN(total)>sizeof(*m)) { fputs("Netlink message too large\n",stderr); exit(1); }
    struct nlattr *a=(void *)((char *)m+pos); a->nla_type=type; a->nla_len=total;
    memcpy((char *)a+NLA_HDRLEN,data,len); m->h.nlmsg_len=pos+NLA_ALIGN(total);
}
static const struct nlattr *find(const void *data, size_t len, unsigned type) {
    while(len>=NLA_HDRLEN) {
        const struct nlattr *a=data;
        if(a->nla_len<NLA_HDRLEN || a->nla_len>len) return NULL;
        if((a->nla_type&NLA_TYPE_MASK)==type) return a;
        size_t step=NLA_ALIGN(a->nla_len); if(step>len)return NULL;
        data=(const char *)data+step; len-=step;
    }
    return NULL;
}
static int value32(const struct nlattr *a, uint32_t *out) {
    if(!a || a->nla_len!=NLA_HDRLEN+sizeof(*out)) { errno=EPROTO; return fail("Invalid driver response"); }
    memcpy(out,(const char *)a+NLA_HDRLEN,sizeof(*out)); return 0;
}
/* Consume matching kernel messages until ACK; retain the one solicited reply. */
static int exchange(struct message *m, unsigned char *reply, size_t capacity) {
    struct sockaddr_nl kernel={.nl_family=AF_NETLINK}; int saved=0;
    if(sendto(sock,m,m->h.nlmsg_len,0,(void *)&kernel,sizeof(kernel))<0)return fail("Netlink send");
    for(int batch=0;batch<32;batch++) {
        unsigned char data[8192]; struct sockaddr_nl from={0};
        struct iovec iov={data,sizeof(data)};
        struct msghdr msg={.msg_name=&from,.msg_namelen=sizeof(from),.msg_iov=&iov,.msg_iovlen=1};
        ssize_t n=recvmsg(sock,&msg,0); if(n<0)return fail("Netlink receive");
        if(msg.msg_flags&MSG_TRUNC) { errno=EMSGSIZE;return fail("Truncated netlink response"); }
        if(from.nl_pid!=0)continue;
        unsigned remaining=(unsigned)n;
        for(struct nlmsghdr *h=(void *)data;NLMSG_OK(h,remaining);h=NLMSG_NEXT(h,remaining)) {
            if(h->nlmsg_seq!=m->h.nlmsg_seq)continue;
            if(h->nlmsg_type==NLMSG_ERROR) {
                if(h->nlmsg_len<NLMSG_LENGTH(sizeof(struct nlmsgerr))) { errno=EPROTO;return fail("Short ACK"); }
                int error=((struct nlmsgerr *)NLMSG_DATA(h))->error;
                if(error) { errno=-error;return fail("Driver rejected request"); }
                return saved;
            }
            if(h->nlmsg_type==m->h.nlmsg_type && h->nlmsg_len>=NLMSG_LENGTH(GENL_HDRLEN)) {
                size_t len=h->nlmsg_len-NLMSG_LENGTH(GENL_HDRLEN);
                if(len>capacity){errno=EMSGSIZE;return fail("Oversized response");}
                memcpy(reply,(char *)NLMSG_DATA(h)+GENL_HDRLEN,len); saved=(int)len;
            }
        }
    }
    errno=EPROTO;return fail("No matching ACK");
}
static int family(void) {
    struct message m; unsigned char reply[4096]; init(&m,GENL_ID_CTRL,CTRL_CMD_GETFAMILY);
    attr(&m,CTRL_ATTR_FAMILY_NAME,"nl80211",sizeof("nl80211"));
    int len=exchange(&m,reply,sizeof(reply));if(len<0)return -1;
    const struct nlattr *a=find(reply,len,CTRL_ATTR_FAMILY_ID); uint16_t id;
    if(!a || a->nla_len!=NLA_HDRLEN+sizeof(id)){errno=EPROTO;return fail("Missing nl80211 family");}
    memcpy(&id,(const char *)a+NLA_HDRLEN,sizeof(id));return id;
}
static int station(int fam,uint32_t index) {
    struct message m; unsigned char reply[4096]; uint32_t type;
    init(&m,fam,NL80211_CMD_GET_INTERFACE);attr(&m,NL80211_ATTR_IFINDEX,&index,sizeof(index));
    int len=exchange(&m,reply,sizeof(reply));if(len<0)return -1;
    if(value32(find(reply,len,NL80211_ATTR_IFTYPE),&type)<0)return -1;
    if(type!=NL80211_IFTYPE_STATION){errno=EOPNOTSUPP;return fail("Not a Wi-Fi station");}
    return 0;
}
static int band(int fam,uint32_t index,int set,uint32_t *mask) {
    struct message m; unsigned char reply[4096]; uint32_t oui=QCA_OUI,cmd=set?QCA_SETBAND:QCA_GETBAND;
    init(&m,fam,NL80211_CMD_VENDOR);attr(&m,NL80211_ATTR_IFINDEX,&index,sizeof(index));
    attr(&m,NL80211_ATTR_VENDOR_ID,&oui,sizeof(oui));attr(&m,NL80211_ATTR_VENDOR_SUBCMD,&cmd,sizeof(cmd));
    if(set) {
        struct { struct nlattr a; uint32_t mask; } nested={{NLA_HDRLEN+sizeof(uint32_t),QCA_BAND_MASK},*mask};
        /* New kernels require this nested flag, absent in stock iw vendor send. */
        attr(&m,NL80211_ATTR_VENDOR_DATA|NLA_F_NESTED,&nested,sizeof(nested));
    }
    int len=exchange(&m,reply,sizeof(reply));if(len<0)return -1;
    if(set)return 0;
    const struct nlattr *vendor=find(reply,len,NL80211_ATTR_VENDOR_DATA);
    if(!vendor){errno=EOPNOTSUPP;return fail("GETBAND unavailable");}
    if(value32(find((const char *)vendor+NLA_HDRLEN,vendor->nla_len-NLA_HDRLEN,QCA_BAND_MASK),mask)<0)return -1;
    if(*mask>7){errno=EOPNOTSUPP;return fail("Unknown band mask");}return 0;
}
int main(int argc,char **argv) {
    int set=argc==4 && !strcmp(argv[1],"set"); uint32_t mask=0,index;
    if(getuid()!=0 || (!set && (argc!=3 || strcmp(argv[1],"get")))
        || (strcmp(argv[2],"wlan0") && strcmp(argv[2],"wlan1"))
        || (set && (strlen(argv[3])!=1 || argv[3][0]<'0' || argv[3][0]>'7'))) {
        fputs("Usage (root): wifi-band get wlan0|wlan1 | set wlan0|wlan1 0..7\n",stderr);return 2;
    }
    index=if_nametoindex(argv[2]);if(!index){errno=ENODEV;fail("Wi-Fi interface unavailable");return 1;}
    sock=socket(AF_NETLINK,SOCK_RAW|SOCK_CLOEXEC,NETLINK_GENERIC);if(sock<0){fail("Netlink socket");return 1;}
    struct timeval limit={.tv_sec=3};setsockopt(sock,SOL_SOCKET,SO_RCVTIMEO,&limit,sizeof(limit));
    struct sockaddr_nl local={.nl_family=AF_NETLINK};
    if(bind(sock,(void *)&local,sizeof(local))<0){fail("Netlink bind");close(sock);return 1;}
    int fam=family(),rc=1;
    if(fam<0 || station(fam,index)<0)goto done;
    if(set) { mask=argv[3][0]-'0';if(band(fam,index,1,&mask)<0)goto done; }
    if(band(fam,index,0,&mask)<0)goto done;
    printf("%u\n",mask);rc=0;
done: close(sock);return rc;
}
