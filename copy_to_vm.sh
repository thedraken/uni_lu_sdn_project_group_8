ssh -p 8088 mininet@127.0.0.1 'mkdir -p ~/group_8'
scp -P 8088 output/*.* mininet@127.0.0.1:~/group_8
scp -r -P 8088 python/* mininet@127.0.0.1:~/group_8
scp -P 8088 *.sh mininet@127.0.0.1:~/group_8
