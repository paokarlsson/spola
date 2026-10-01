#!/bin/bash
# Default-deny egress firewall. Only GitHub + domains in allowed-domains.txt are reachable.
#   init-firewall.sh            full setup (run at container start)
#   init-firewall.sh --refresh  re-resolve domains and add new IPs (CDNs rotate IPs)
set -euo pipefail
IFS=$'\n\t'
DOMAINS_FILE=/usr/local/bin/allowed-domains.txt

add_domains() {
  grep -vE '^\s*(#|$)' "$DOMAINS_FILE" | while read -r domain; do
    ips=$(dig +noall +answer A "$domain" | awk '$4 == "A" {print $5}')
    if [ -z "$ips" ]; then
      echo "WARN: could not resolve $domain" >&2
      continue
    fi
    for ip in $ips; do
      [[ "$ip" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || { echo "WARN: bad IP $ip for $domain" >&2; continue; }
      ipset add -exist allowed-domains "$ip"
    done
  done
}

if [ "${1:-}" = "--refresh" ]; then
  add_domains
  exit 0
fi

DOCKER_DNS_RULES=$(iptables-save -t nat | grep "127\.0\.0\.11" || true)
iptables -F; iptables -X
iptables -t nat -F; iptables -t nat -X
iptables -t mangle -F; iptables -t mangle -X
ipset destroy allowed-domains 2>/dev/null || true

if [ -n "$DOCKER_DNS_RULES" ]; then
  iptables -t nat -N DOCKER_OUTPUT 2>/dev/null || true
  iptables -t nat -N DOCKER_POSTROUTING 2>/dev/null || true
  echo "$DOCKER_DNS_RULES" | xargs -L 1 iptables -t nat
fi

iptables -A OUTPUT -p udp --dport 53 -j ACCEPT
iptables -A INPUT  -p udp --sport 53 -j ACCEPT
iptables -A OUTPUT -p tcp --dport 22 -j ACCEPT
iptables -A INPUT  -p tcp --sport 22 -m state --state ESTABLISHED -j ACCEPT
iptables -A INPUT  -i lo -j ACCEPT
iptables -A OUTPUT -o lo -j ACCEPT

ipset create allowed-domains hash:net

gh_ranges=$(curl -s https://api.github.com/meta)
echo "$gh_ranges" | jq -e '.web and .api and .git' >/dev/null || { echo "ERROR: bad GitHub meta response"; exit 1; }
while read -r cidr; do
  [[ "$cidr" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}/[0-9]{1,2}$ ]] || { echo "ERROR: bad CIDR $cidr"; exit 1; }
  ipset add -exist allowed-domains "$cidr"
done < <(echo "$gh_ranges" | jq -r '(.web + .api + .git)[]' | aggregate -q)

add_domains

HOST_IP=$(ip route | grep default | cut -d" " -f3)
[ -n "$HOST_IP" ] || { echo "ERROR: no host IP"; exit 1; }
HOST_NETWORK=$(echo "$HOST_IP" | sed "s/\.[0-9]*$/.0\/24/")
iptables -A INPUT  -s "$HOST_NETWORK" -j ACCEPT
iptables -A OUTPUT -d "$HOST_NETWORK" -j ACCEPT

iptables -P INPUT DROP
iptables -P FORWARD DROP
iptables -P OUTPUT DROP
iptables -A INPUT  -m state --state ESTABLISHED,RELATED -j ACCEPT
iptables -A OUTPUT -m state --state ESTABLISHED,RELATED -j ACCEPT
iptables -A OUTPUT -m set --match-set allowed-domains dst -j ACCEPT
iptables -A OUTPUT -j REJECT --reject-with icmp-admin-prohibited

if curl --connect-timeout 5 -s https://example.com >/dev/null; then
  echo "ERROR: firewall leak, example.com reachable"; exit 1
fi
curl --connect-timeout 5 -s https://api.github.com/zen >/dev/null || { echo "ERROR: GitHub unreachable"; exit 1; }
echo "Firewall OK"

# Long runs: CDN IPs (npm, Anthropic) rotate, so re-resolve every 5 min (ipset only grows, old IPs stay allowed).
nohup bash -c 'while sleep 300; do /usr/local/bin/init-firewall.sh --refresh; done' >/tmp/firewall-refresh.log 2>&1 &
