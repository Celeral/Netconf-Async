How to use this code to create Java class for Juniper?

Assuming 
pyang is installed in ~/projects/pyang-pyang-2.1 folder.
Netconf-Async is in ~/projects/Netconf-Async

a) Go to pyang install root ~/projects/pyang-pyang-2.1
b) Source env

```
source ./env.sh
```
c) Now you can run pyang to make sure its function. If not then fix that.

```
pyang -h
```

d)
get Juniper yang files:

```
mkdir ~/projects/juniper
cd ~/projects/juniper
git clone https://github.com/Juniper/yang.git
```

e) Now go to Netconf-Async folder

```
cd ~/projects/Netconf-Async
```

f) Execute java code genaration using a command similar to shown below:

```
developer@rapti:~/projects/Netconf-Async$ pyang --jnc-no-pkginfo --lax-quote-checks  --plugindir jnc  -f jnc --jnc-output src/org/himalay/juniper -p /home/developer/projects/juniper/yang/17.3/17.3R1/common --dsdl-no-documentation /home/developer/projects/juniper/yang/17.3/17.3R1/junos-es/configuration.yang
```

if you need to generate package info then remove --jnc-no-pkginfo. Generated package info and schema has one character per line which is not good. This can be fixed using following command:

```
find src/org/himalay/juniper -name package-info.java -exec sed -z -r  -i 's/([^\n])\n/\1/g' {} \;
find src/org/himalay/juniper -name *.schema -exec sed -z -r  -i 's/([^\n])\n/\1/g' {} \;
```
