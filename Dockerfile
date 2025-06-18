FROM amazonlinux:2

ENV LANG="en_GB.UTF-8" LANGUAGE="en_GB:en" LC_ALL="en_GB.UTF-8"
RUN rpm --import https://yum.corretto.aws/corretto.key
RUN curl -L -o /etc/yum.repos.d/corretto.repo https://yum.corretto.aws/corretto.repo
RUN yum install -y java-21-openjdk-devel
RUN curl -L https://www.scala-sbt.org/sbt-rpm.repo > /etc/yum.repos.d/sbt-rpm.repo
RUN yum install -y sbt-1.10.10
RUN sbt --allow-empty -Dsbt.rootdir=true update
